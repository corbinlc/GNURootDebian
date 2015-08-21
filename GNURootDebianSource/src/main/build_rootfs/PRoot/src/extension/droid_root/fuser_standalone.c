/*
 * Licensed under GPLv2, see file LICENSE in this source tree.
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <dirent.h>
#include <errno.h>
#include <string.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include <signal.h>
#include "fuser.h"

#define MAX_LINE 255

int recursion_depth = 0;
pid_t mypid;
char **used_paths;
int num_used_paths;
char **unused_paths;
int num_unused_paths;


#define DOT_OR_DOTDOT(s) ((s)[0] == '.' && (!(s)[1] || ((s)[1] == '.' && !(s)[2])))

enum {
	PROC_NET = 0,
	PROC_DIR,
	PROC_DIR_LINKS,
	PROC_SUBDIR_LINKS,
};

int index_in_substrings(const char *strings, const char *key)
{
	int matched_idx = -1;
	const int len = strlen(key);

	if (len) {
		int idx = 0;
		while (*strings) {
			if (strncmp(strings, key, len) == 0) {
				if (strings[len] == '\0')
					return idx; /* exact match */
				if (matched_idx >= 0)
					return -1; /* ambiguous match */
				matched_idx = idx;
			}
			strings += strlen(strings) + 1; /* skip NUL */
			idx++;
		}
	}
	return matched_idx;
}

char* last_char_is(const char *s, int c)
{
	if (s && *s) {
		size_t sz = strlen(s) - 1;
		s += sz;
		if ( (unsigned char)*s == c)
			return (char*)s;
	}
	return NULL;
}

char* concat_path_file(const char *path, const char *filename)
{
	char *outbuf;
	char *lc;

	if (!path)
		path = "";
	lc = last_char_is(path, '/');
	while (*filename == '/')
		filename++;
	outbuf = malloc(strlen(path)+strlen(filename)+1+(lc==NULL));
	sprintf(outbuf, "%s%s%s", path, (lc==NULL ? "/" : ""), filename);

	return outbuf;
}

int init_fuser()
{
	printf("init_fuser starting\n");
	used_paths = NULL;
	num_used_paths = 0;
	unused_paths = NULL;
	num_unused_paths = 0;
	printf("init_fuser done\n");
	return 0;
}

int free_unused_paths()
{
	int i;
	for (i = 0; i < num_unused_paths; i++) {
		free(unused_paths[i]);
	}
	free(unused_paths);
	return 0;
}

int add_unused_path(const char path[PATH_MAX])
{
	num_unused_paths++;
	unused_paths = realloc(unused_paths,num_unused_paths*sizeof(char*));
	unused_paths[num_unused_paths-1] = malloc(PATH_MAX*sizeof(char));
	strcpy(unused_paths[num_unused_paths-1],path);
	return 0;
}

int free_used_paths()
{
	int i;
	printf("starting free_used_paths\n");
	for (i = 0; i < num_used_paths; i++) {
		free(used_paths[i]);
	}
	free(used_paths);
	used_paths = NULL;
	num_used_paths = 0;
	return 0;
}

int add_used_path(const char path[PATH_MAX])
{
	char **tmp;
	char link_path[PATH_MAX];
	int size;

	printf("starting add_used_path\n");
	printf("starting add_used_path path: %s\n", path);
	size = readlink(path, link_path, PATH_MAX);	
	if (size < 0)
		return 0;
	link_path[size] = '\0';

	num_used_paths++;
	tmp = realloc(used_paths,num_used_paths*sizeof(char*));
	if (tmp == NULL)
		printf("add_used_paths error growing array of strings\n");
	used_paths = tmp;
	used_paths[num_used_paths-1] = malloc(PATH_MAX*sizeof(char));
	strcpy(used_paths[num_used_paths-1],link_path);
	printf("finishing add_used_path added path: %s\n", used_paths[num_used_paths-1]);
	return 0;
}

char* concat_subpath_file(const char *path, const char *f)
{
	if (f && DOT_OR_DOTDOT(f))
		return NULL;
	return concat_path_file(path, f);
}

int scan_proc_maps(const char *path, dev_t device, ino_t inode, bool save_all)
{
	FILE *f;
	char line[MAX_LINE + 1];
	int major, minor, r;
	long long uint64_inode;
	int retval;
	struct stat statbuf;
	const char *fmt;
	void *fag, *sag;

	f = fopen(path,"r");
	if (!f)
		return 0;

	fmt = "%*s %*s %*s %x:%x %llu";
	fag = &major;
	sag = &minor;

	retval = 0;
	while (fgets(line, MAX_LINE, f)) {
		r = sscanf(line, fmt, fag, sag, &uint64_inode);
		if (r != 3)
			continue;

		statbuf.st_ino = uint64_inode;

		if (major != 0 && minor != 0 && statbuf.st_ino != 0) {
			statbuf.st_dev = makedev(major, minor);
			retval = (statbuf.st_ino == inode) & (statbuf.st_dev == device) & (!save_all);
			if (save_all)
				add_used_path(path);
			if (retval)
				break;
		}
	}
	fclose(f);

	return retval;
}

int scan_recursive(const char *path, dev_t device, ino_t inode, bool save_all, pid_t proc_of_interest)
{
	DIR *d;
	struct dirent *d_ent;
	int stop_scan;
	int retval;

	d = opendir(path);
	if (d == NULL)
		return 0;

	recursion_depth++;
	retval = 0;
	stop_scan = 0;
	while (!stop_scan && (d_ent = readdir(d)) != NULL) {
		struct stat statbuf;
		pid_t pid;
		char *subpath;

		subpath = concat_subpath_file(path, d_ent->d_name);
		if (subpath == NULL)
			continue; /* . or .. */

		switch (recursion_depth) {
		case PROC_DIR:
			pid = (pid_t)strtoul(d_ent->d_name, NULL, 10);
			if (errno != 0
			 || pid == mypid
			 || ((pid != proc_of_interest) && (proc_of_interest != 0))
			/* "this PID doesn't use specified FILEs or PORT/PROTO": */
			 || scan_recursive(subpath, device, inode, save_all, proc_of_interest) == 0
			) {
				errno = 0;
				break;
			}
			retval = 1;
			break;

		case PROC_DIR_LINKS:
			switch (
				index_in_substrings(
					"cwd"  "\0" "exe"  "\0"
					"root" "\0" "fd"   "\0"
					"lib"  "\0" "mmap" "\0"
					"maps" "\0",
					d_ent->d_name
				)
			) {
			enum {
				CWD_LINK,
				EXE_LINK,
				ROOT_LINK,
				FD_DIR_LINKS,
				LIB_DIR_LINKS,
				MMAP_DIR_LINKS,
				MAPS,
			};
			case CWD_LINK:
			case EXE_LINK:
			case ROOT_LINK:
				goto scan_link;
			case FD_DIR_LINKS:
			case LIB_DIR_LINKS:
			case MMAP_DIR_LINKS:
				stop_scan = scan_recursive(subpath, device, inode, save_all, proc_of_interest);
				if (stop_scan)
					retval = stop_scan;
				break;
			case MAPS:
				//stop_scan = scan_proc_maps(subpath, device, inode, save_all);
				stop_scan = 0;
				if (stop_scan)
					retval = stop_scan;
			default:
				break;
			}
			break;
		case PROC_SUBDIR_LINKS:
  scan_link:
			if (stat(subpath, &statbuf) < 0)
				break;
			stop_scan = (statbuf.st_ino == inode) & (statbuf.st_dev == device) & (!save_all);
			if (save_all)
				add_used_path(subpath);
			if (stop_scan)
				retval = stop_scan;
		default:
			break;
		}
		free(subpath);
	}
	closedir(d);
	recursion_depth--;
	return retval;
}

//check if a file is in use by any process
//if it is, return 0
//if it isn't, return 1
int is_unused(char path[PATH_MAX]) 
{
	struct stat statbuf;

	stat(path, &statbuf);

	recursion_depth = 0;
	mypid = getpid();
	return ~scan_recursive("/proc", statbuf.st_dev, statbuf.st_ino, false, 0);	
}

//fill up the used_paths string array with all the paths that are used by a given process
//returns 0 always, for now
int update_used_paths(pid_t proc)
{
	printf("update_used_paths starting\n");
	free_used_paths();

	recursion_depth = 0;
	mypid = getpid();
	scan_recursive("/proc", 0, 0, true, proc);	

	return 0;
}

//fill up the unused_paths string array with all the paths that were used, but now aren't
//returns 0 always, for now
int update_unused_paths()
{
	int i;
	struct stat statbuf;

	free_unused_paths();

	for (i=0; i<num_used_paths; i++) {
		stat(used_paths[i], &statbuf);

		recursion_depth = 0;
		mypid = getpid();
		if (scan_recursive("/proc", statbuf.st_dev, statbuf.st_ino, false, 0) == 0) {
			add_unused_path(used_paths[i]);
		}	
	}

	return 0;

}

int main(int argc, char **argv)
{
	printf("main starting\n");
	init_fuser();
	update_used_paths(10681);
	update_used_paths(10681);
	update_used_paths(10681);
	update_used_paths(10681);
	update_used_paths(10681);
	printf("main finishing\n");
	return 0;
}
