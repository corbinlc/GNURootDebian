#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <errno.h>
#include <utime.h>
#include <limits.h> /* PATH_MAX */
#include <sys/param.h> /* MAXSYMLINKS, */
#include <linux/binfmts.h> /* BINPRM_BUF_SIZE, */

#include "extension/extension.h"
#include "syscall/syscall.h"
#include "tracee/tracee.h"
#include "tracee/mem.h"
#include "path/path.h"
#include "fuser.h"

#define SHADOW_PATH "/.proot.noexec/"
#define SHADOW_LINK_PREFIX ".proot.shadow."
#define DELETED_SUFFIX " (deleted)"

char close_path[PATH_MAX];
int close_path_valid;

//get the filename from a path
static char * get_name(char path[PATH_MAX])
{
	char *name;

	name = strrchr(path,'/');
	if (name == NULL)
		name = path;
	else
		name++;

	return name;
}

static int my_readlink(Tracee *tracee, char symlink[PATH_MAX], char value[PATH_MAX])
{
	ssize_t size;
	char temp[PATH_MAX];
	int status;
	char *name;

	size = readlink(symlink, value, PATH_MAX);
	if (size < 0)
		return size;
	if (size >= PATH_MAX)
		return -ENAMETOOLONG;
	value[size] = '\0';

	//always make the result be an absolute path
	if (value[0] != '/') {
		name = get_name(symlink);
		strncpy(temp, symlink, strlen(symlink) - strlen(name));
		temp[strlen(symlink) - strlen(name)] = '\0';
		strcat(temp, value);
		strcpy(value, temp);
	}

	status = translate_path(tracee, temp, AT_FDCWD, value, false); 
	if (status == 0)
		strcpy(value, temp);

	return 0;
}

static int my_symlink(Tracee *tracee, const char old_path[PATH_MAX], char new_path[PATH_MAX])
{
	int status;
	char shadow_path[PATH_MAX];
	char temp_path[PATH_MAX];
 
	status = translate_path(tracee, shadow_path, AT_FDCWD, SHADOW_PATH, false); 
	if (status < 0)
		return status;

	strcpy(temp_path, old_path);
	if (strncmp(temp_path, shadow_path, strlen(shadow_path)) == 0) {
		status = detranslate_path(tracee, temp_path, NULL);
		if (status < 0)
			return status;
	}

	status = symlink(temp_path, new_path);
	if (status < 0)
		return status;
	
	return 0;
}

static int get_dir_path(char path[PATH_MAX], char dir_path[PATH_MAX])
{
	int offset;

	strcpy(dir_path, path);
	offset = strlen(dir_path) - 1;
	if (offset > 0) {
		/* Skip trailing path separators. */
		while (offset > 1 && dir_path[offset] == '/')
			offset--;

		/* Search for the previous path separator. */
		while (offset > 1 && dir_path[offset] != '/')
			offset--;

		/* Cut the end of the string before the last component. */
		dir_path[offset] = '\0';
	}
	return 0;
}

static int copy_file(char old_path[PATH_MAX], char new_path[PATH_MAX])
{
	char buf[BUFSIZ];
	size_t size;
	int source;
	int dest;

	source = open(old_path, O_RDONLY);
	if (source < 0) {
		return source;
	}
	dest = open(new_path, O_WRONLY);
	if (dest < 0) {
		return dest;
	}

	while ((size = read(source, buf, BUFSIZ)) > 0) {
		write(dest, buf, size);
	}

	close(source);
	close(dest);

	return 0;
}

/******************************************************
 * Get a path for a new shadow file  
 ******************************************************/
static int new_shadow_file_path(Tracee *tracee, char file_path[PATH_MAX]) {
	int directory;
	int file;
	long itteration;
	char temp_path[PATH_MAX];
	char dir_path[PATH_MAX];
	int status;

	//find a file name that hasn't been used yet
	for (itteration = 0; itteration < 1000000; itteration++) {
		directory = rand() % 10000;
		file = rand() % 10000;
		sprintf(temp_path, "%s%04d/", SHADOW_PATH, directory);		
		status = translate_path(tracee, dir_path, AT_FDCWD, temp_path, false); 
		if (status < 0)
			return status;
		sprintf(file_path, "%s%04d", dir_path, file);		
		if (access(file_path, F_OK) == -1) {
			mkdir(dir_path, 0777);
			return 0;
		}
	}

	return -1;
} 

static int get_shadow_link_path(char root_path[PATH_MAX], char shadow_link_path[PATH_MAX])
{
	char *name;

	if (strlen(SHADOW_LINK_PREFIX) + strlen(root_path) >= PATH_MAX)
		return -ENAMETOOLONG;

	name = get_name(root_path);

	strncpy(shadow_link_path, root_path, strlen(root_path) - strlen(name));
	shadow_link_path[strlen(root_path) - strlen(name)] = '\0';
	strcat(shadow_link_path, SHADOW_LINK_PREFIX);
	strcat(shadow_link_path, name);
	return 0;
}

static int root_to_shadow(Tracee *tracee, char root_path[PATH_MAX])
{
	int status;
	char shadow_path[PATH_MAX];
	char shadow_link_path[PATH_MAX];
	struct stat time_stat;
	struct utimbuf time_struct;
	char dir_path[PATH_MAX];
	struct stat dir_time_stat;
	struct utimbuf dir_time_struct;

	if (!belongs_to_guestfs(tracee, root_path))
		return 0;

	status = lstat(root_path, &time_stat);
	if (status < 0)
		return status;
	if (!S_ISREG(time_stat.st_mode))
		return 0;	
	time_struct.actime = time_stat.st_atime;
	time_struct.modtime = time_stat.st_mtime;

	status = get_dir_path(root_path, dir_path);
	status = lstat(dir_path, &dir_time_stat);
	if (status < 0)
		return status;
	dir_time_struct.actime = dir_time_stat.st_atime;
	dir_time_struct.modtime = dir_time_stat.st_mtime;

	status = new_shadow_file_path(tracee, shadow_path);
	if (status < 0)
		return status;

	status = creat(shadow_path, 0777);
	if (status < 0)
		return status;
	close(status);

	status = copy_file(root_path, shadow_path);
	if (status < 0)
		return status;

	status = truncate(root_path, 0);
	if (status < 0)
		return status;

	status = get_shadow_link_path(root_path, shadow_link_path);
	if (status < 0)
		return status;

	status = my_symlink(tracee, shadow_path, shadow_link_path);
	if (status < 0)
		return status;

	status = utime(root_path, &time_struct);
	if (status < 0)
		return status;
	status = utime(dir_path, &dir_time_struct);
	if (status < 0)
		return status;

	return 0;
}

static int shadow_to_root(Tracee *tracee, char root_path[PATH_MAX])
{
	int status;
	int size;
	char shadow_path[PATH_MAX];
	char shadow_link_path[PATH_MAX];
	struct stat time_stat;
	struct utimbuf time_struct;
	char dir_path[PATH_MAX];
	struct stat dir_time_stat;
	struct utimbuf dir_time_struct;

	if (!belongs_to_guestfs(tracee, root_path))
		return 0;

	status = lstat(root_path, &time_stat);
	if (status < 0)
		return 0;
	if (!S_ISREG(time_stat.st_mode))
		return 0;	
	time_struct.actime = time_stat.st_atime;
	time_struct.modtime = time_stat.st_mtime;

	status = get_dir_path(root_path, dir_path);
	status = lstat(root_path, &time_stat);
	status = lstat(dir_path, &dir_time_stat);
	if (status < 0)
		return status;
	dir_time_struct.actime = dir_time_stat.st_atime;
	dir_time_struct.modtime = dir_time_stat.st_mtime;

	status = get_shadow_link_path(root_path, shadow_link_path);
	if (status < 0)
		return status;

	size = my_readlink(tracee, shadow_link_path, shadow_path);
	//if there is no shadow file, get out
	if (size < 0)
		return 0;

	status = copy_file(shadow_path, root_path);
	if (status < 0)
		return status;

	status = unlink(shadow_path);
	if (status < 0)
		return status;

	status = unlink(shadow_link_path);
	if (status < 0)
		return status;

	status = utime(root_path, &time_struct);
	if (status < 0)
		return status;
	status = utime(dir_path, &dir_time_struct);
	if (status < 0)
		return status;
	
	return 0;

}

//if the file has a shadow file and shadow link file, delete those too
static int handle_unlink_core(Tracee *tracee, char orig_path[PATH_MAX])
{
	int status;
	int size;
	char shadow_path[PATH_MAX];
	char shadow_link_path[PATH_MAX];

	status = get_shadow_link_path(orig_path, shadow_link_path);
	if (status < 0)
		return status;

	size = my_readlink(tracee, shadow_link_path, shadow_path);
	if (size > 0) {
		unlink(shadow_link_path);
		unlink(shadow_path);
	}

	return 0;
}

//if the file has a shadow file and shadow link file, delete those too
static int handle_unlink(Tracee *tracee, Reg path_sysarg)
{
	int size;
	char orig_path[PATH_MAX];

	size = read_string(tracee, orig_path, peek_reg(tracee, CURRENT, path_sysarg), PATH_MAX);
	if (size < 0)
		return size;
	if (size >= PATH_MAX)
		return -ENAMETOOLONG;

	return handle_unlink_core(tracee, orig_path);
}

//if the oldpath file has a shadow link file, rename that too
//if the newpath file has a shadow file and shadow link file, delete those too
static int handle_rename_core(Tracee *tracee, char old_path[PATH_MAX], char new_path[PATH_MAX])
{
	int status;
	int size;
	char shadow_path[PATH_MAX];
	char old_shadow_link_path[PATH_MAX];
	char new_shadow_link_path[PATH_MAX];

	//first deal with new path
	status = get_shadow_link_path(new_path, new_shadow_link_path);
	if (status < 0)
		return status;

	size = my_readlink(tracee, new_shadow_link_path, shadow_path);
	if (size > 0) {
		unlink(new_shadow_link_path);
		unlink(shadow_path);
	}

	//now deal with old path
	status = get_shadow_link_path(old_path, old_shadow_link_path);
	if (status < 0)
		return status;

	size = my_readlink(tracee, old_shadow_link_path, shadow_path);
	if (size < 0)
		return 0;

	status = rename(old_shadow_link_path, new_shadow_link_path);
	if (status < 0)
		return status;

	return 0;
}

//if the oldpath file has a shadow link file, rename that too
//if the newpath file has a shadow file and shadow link file, delete those too
static int handle_rename(Tracee *tracee, Reg oldpath_sysarg, Reg newpath_sysarg)
{
	int size;
	char old_path[PATH_MAX];
	char new_path[PATH_MAX];

	size = read_string(tracee, new_path, peek_reg(tracee, CURRENT, newpath_sysarg), PATH_MAX);
	if (size < 0)
		return size;
	if (size >= PATH_MAX)
		return -ENAMETOOLONG;

	size = read_string(tracee, old_path, peek_reg(tracee, CURRENT, oldpath_sysarg), PATH_MAX);
	if (size < 0)
		return size;
	if (size >= PATH_MAX)
		return -ENAMETOOLONG;

	return handle_rename_core(tracee, old_path, new_path);
}

//on truncate, truncate the shadow file if there is one
static int handle_truncate(Tracee *tracee, Reg path_sysarg)
{
	int status;
	int size;
	char orig_path[PATH_MAX];
	char shadow_path[PATH_MAX];
	char shadow_link_path[PATH_MAX];

	size = read_string(tracee, orig_path, peek_reg(tracee, CURRENT, path_sysarg), PATH_MAX);
	if (size < 0)
		return size;
	if (size >= PATH_MAX)
		return -ENAMETOOLONG;

	status = get_shadow_link_path(orig_path, shadow_link_path);
	if (status < 0)
		return status;

	size = my_readlink(tracee, shadow_link_path, shadow_path);
	//if there is no shadow file, get out
	if (size < 0)
		return 0;

	return set_sysarg_path(tracee, shadow_path, path_sysarg);
}

//on open, copy a file from shadow to the rootfs if there is a shadow file
static int handle_open(Tracee *tracee, Reg path_sysarg)
{
	int size;
	char orig_path[PATH_MAX];

	//get path from sysarg
	size = read_string(tracee, orig_path, peek_reg(tracee, CURRENT, path_sysarg), PATH_MAX);
	if (size < 0)
                return size;
        if (size >= PATH_MAX)
                return -ENAMETOOLONG;

	//copy shadow file to that path
	return shadow_to_root(tracee, orig_path);
}

//when process is getting ready to exit, push back anything that is only being used by that process
static int handle_exit_enter(Tracee *tracee)
{
	int i;

	update_used_paths(tracee->pid);
	update_unused_paths(tracee->pid);
	for (i=0; i<num_unused_paths; i++)
		root_to_shadow(tracee, unused_paths[i]);

	return 0;
}

//figure out what file is being unmapped, so we can check later if we can push it back to the shadow
//TODO, this should be changed to be based on inode and dev, since maps path might not be accurate
static int handle_munmap_enter(Tracee *tracee, Reg address_sysarg)
{
	update_mapped_file(tracee->pid, (void *)peek_reg(tracee, ORIGINAL, address_sysarg));
	return 0;
}

static int handle_munmap_exit(Tracee *tracee)
{
	int status;
	struct stat statl;

	if (mapped_file_valid == 0)
		return 0;

	status = lstat(mapped_file, &statl);
	//if file doesn't exist, get out 
	if (status < 0)
		return 0;

	//if it is not a regular file get out
	if (!S_ISREG(statl.st_mode))
		return 0;	

	if (is_unused(mapped_file)) {
		status = root_to_shadow(tracee, mapped_file);
		if (status < 0)
			return 0;
	}

	return 0;
}

//keep track of what file is closed, so we can see if on exit whether we can push it to the shadow
static int handle_close_enter(Tracee *tracee, Reg fd_sysarg)
{
	int status;

	close_path_valid = 0;

	status = readlink_proc_pid_fd(tracee->pid, peek_reg(tracee, CURRENT, fd_sysarg), close_path);
	if (status < 0)
		return status;

	if (strcmp(close_path + strlen(close_path) - strlen(DELETED_SUFFIX), DELETED_SUFFIX) == 0)
		close_path[strlen(close_path) - strlen(DELETED_SUFFIX)] = '\0'; 

	if (belongs_to_guestfs(tracee, close_path))
		close_path_valid = 1;	

	return 0;
}

//if file closed is now no longer used, push it back to the shadow
static int handle_close_exit(Tracee *tracee)
{
	int status;
	struct stat statl;

	if (close_path_valid == 0)
		return 0;

	status = lstat(close_path, &statl);
	//if file doesn't exist, get out 
	if (status < 0)
		return 0;

	//if it is not a regular file get out
	if (!S_ISREG(statl.st_mode))
		return 0;	

	if (is_unused(close_path)) {
		status = root_to_shadow(tracee, close_path);
		if (status < 0)
			return status;
	}

	return 0;
}

//modify the size based on the shadow file, if there is one
static int handle_stat(Tracee *tracee, Reg path_sysarg, Reg stat_struct_sysarg)
{
	int status;
	int size;
	char orig_path[PATH_MAX];
	char shadow_path[PATH_MAX];
	char shadow_link_path[PATH_MAX];
	struct stat statl;
	struct stat shadow_statl;

	size = read_string(tracee, orig_path, peek_reg(tracee, MODIFIED, path_sysarg), PATH_MAX);
	if (size < 0)
		return size;
	if (size >= PATH_MAX)
		return -ENAMETOOLONG;

	status = get_shadow_link_path(orig_path, shadow_link_path);
	if (status < 0)
		return status;

	size = my_readlink(tracee, shadow_link_path, shadow_path);
	//if there is no shadow file, get out
	if (size < 0)
		return 0;

	//get original stat structure
	status = read_data(tracee, &statl, peek_reg(tracee, MODIFIED, stat_struct_sysarg), sizeof(statl));
	if (status < 0) {
		return status;
	}

	//get shadow stat structure
	status = lstat(shadow_path, &shadow_statl);
	if (status < 0)
		return status;

	statl.st_size = shadow_statl.st_size;
	statl.st_blksize = shadow_statl.st_blksize;
	statl.st_blocks = shadow_statl.st_blocks;

	status = write_data(tracee, peek_reg(tracee, MODIFIED,  stat_struct_sysarg), &statl, sizeof(statl));
	if (status < 0)
		return status;

	return 0;
}

/* List of syscalls handled by this extensions.  */
int handle_init(Extension *extension)
{
	static FilteredSysnum filtered_sysnums[] = {
		/*system calls that can push a file from the rootfs to the shadow and create a shadow link file*/
		{ PR_close,		FILTER_SYSEXIT },
		{ PR_dup2,		FILTER_SYSEXIT },
		{ PR_dup3,		FILTER_SYSEXIT },
		{ PR_exit,		FILTER_SYSEXIT },
		{ PR_exit_group,	FILTER_SYSEXIT },
		{ PR_munmap,		FILTER_SYSEXIT },
		/*system calls that can copy a file from the shadow to the rootfs*/
		{ PR_creat,		FILTER_SYSEXIT },
		{ PR_openat,		FILTER_SYSEXIT },
		{ PR_open,		FILTER_SYSEXIT },
		/*{PR_execve,		FILTER_SYSEXIT }, //handled differently through notify extension*/
		/*system calls that can remove the shadow file and shadow link file*/
		{ PR_unlink,            FILTER_SYSEXIT },
		{ PR_unlinkat,          FILTER_SYSEXIT },
		/*system calls that can rename the shadow link file*/
		{ PR_rename,            FILTER_SYSEXIT },
		{ PR_renameat,          FILTER_SYSEXIT },
		/*stat system calls where we can modify the observed size based on the shadow size*/
		{ PR_fstatat64,         FILTER_SYSEXIT },
		{ PR_newfstatat,        FILTER_SYSEXIT },
		{ PR_lstat,             FILTER_SYSEXIT },
		{ PR_lstat64,		FILTER_SYSEXIT },
		{ PR_oldlstat,		FILTER_SYSEXIT },
		{ PR_stat,		FILTER_SYSEXIT },
		{ PR_stat64,		FILTER_SYSEXIT },
		{ PR_oldstat,		FILTER_SYSEXIT },
		/*system calls that can truncate the shadow file*/
		{ PR_truncate,		FILTER_SYSEXIT },
		{ PR_truncate64,	FILTER_SYSEXIT },
		FILTERED_SYSNUM_END,
		};
	extension->filtered_sysnums = filtered_sysnums;
	return 0;
}

static int handle_sysenter_end(Tracee *tracee)
{
	word_t sysnum;
	Reg sysarg = SYSARG_NUM;
	Reg sysarg2 = SYSARG_NUM;
	int status;

        sysnum = get_sysnum(tracee, ORIGINAL);

        switch (sysnum) {
	/*system calls that can push a file from the rootfs to the shadow and create a shadow link file*/
	case PR_dup2:                      //int dup2(int oldfd, int newfd);
	case PR_dup3:                      //int dup3(int oldfd, int newfd, int flags);
		sysarg++;
	case PR_close:                     //int close(int fd);
		sysarg++;
		status = handle_close_enter(tracee, sysarg);
		if (status < 0)
			return status;
		break;
	case PR_exit:                      //void _exit(int status);
	case PR_exit_group:                //void exit_group(int status);
		status = handle_exit_enter(tracee);
		if (status < 0)
			return status;
		break;
	case PR_munmap:                    //int munmap(void *addr, size_t length);
		sysarg++;
		handle_munmap_enter(tracee,sysarg);
		break;
	/*system calls that can copy a file from the shadow to the rootfs*/
	case PR_openat: //int openat(int dirfd, const char *pathname, int flags, mode_t mode);
		sysarg++;
	case PR_open: //int open(const char *pathname, int flags, mode_t mode);
	case PR_creat: //int creat(const char *pathname, mode_t mode);
		sysarg++;
		status = handle_open(tracee, sysarg);
		if (status < 0)
			return status;
		break;
	/*system calls that can remove the shadow file and shadow link file*/
	case PR_unlinkat: //int unlinkat(int dirfd, const char *pathname, int flags);
		sysarg++;
	case PR_unlink: //int unlink(const char *pathname);
		sysarg++;
		status = handle_unlink(tracee, sysarg);
		if (status < 0)
			return status;
		break;
	/*system calls that can rename the shadow link file*/
	case PR_renameat: //int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath);
		sysarg = SYSARG_2;
		sysarg2 = SYSARG_4;
		status = handle_rename(tracee, sysarg, sysarg2);
		if (status < 0)
			return status;
		break;
	case PR_rename: //int rename(const char *oldpath, const char *newpath);
		sysarg = SYSARG_1;
		sysarg2 = SYSARG_2;
		status = handle_rename(tracee, sysarg, sysarg2);
		if (status < 0)
			return status;
		break;
	/*system calls that can truncate the shadow file*/
	case PR_truncate:
	case PR_truncate64:
		sysarg++;
		status = handle_truncate(tracee, sysarg);
		if (status < 0)
			return status;
		break;
	default:
		break;
	}
	return 0;
}

static int handle_sysexit_end(Tracee *tracee)
{
	word_t sysnum;
	Reg sysarg = SYSARG_NUM;
	Reg sysarg2 = SYSARG_NUM;
	int status;

        sysnum = get_sysnum(tracee, ORIGINAL);

        switch (sysnum) {
	
	/*system calls that can push a file from the rootfs to the shadow and create a shadow link file*/
	case PR_dup2:                      //int dup2(int oldfd, int newfd);
	case PR_dup3:                      //int dup3(int oldfd, int newfd, int flags);
	case PR_close:                     //int close(int fd);
		handle_close_exit(tracee);
		break;
	case PR_munmap:                    //int munmap(void *addr, size_t length);
		handle_munmap_exit(tracee);
		break;
	/*stat system calls where we can modify the observed size based on the shadow size*/
        case PR_fstatat64:                 //int fstatat(int dirfd, const char *pathname, struct stat *buf, int flags);
		sysarg++;
        case PR_newfstatat:                //int fstatat(int dirfd, const char *pathname, struct stat *buf, int flags);
        case PR_stat:                      //int stat(const char *path, struct stat *buf);
        case PR_stat64:                    //int stat(const char *path, struct stat *buf);
        case PR_oldstat:                   //int stat(const char *path, struct stat *buf); 
        case PR_lstat:                     //int lstat(const char *path, struct stat *buf);
        case PR_lstat64:                   //int lstat(const char *path, struct stat *buf);
        case PR_oldlstat:                  //int lstat(const char *path, struct stat *buf); 
		sysarg++;
		sysarg2 = sysarg + 1;
		status = handle_stat(tracee, sysarg, sysarg2);
		if (status < 0)
			return status;
		break;
	default:
		break;
	}
	return 0;
}

static int handle_exec_path(Tracee *tracee, char exec_path[PATH_MAX])
{
	return shadow_to_root(tracee, exec_path);
}

int handle_new_status(Tracee *tracee, int tracee_status)
{
	if ((WIFEXITED(tracee_status)) || (WIFSIGNALED(tracee_status))) {
		handle_exit_enter(tracee);
	}

	return 0;
}

/**
 * Handler for this @extension.  It is triggered each time an @event
 * occurred.  See ExtensionEvent for the meaning of @data1 and @data2.
 */
int noexec_shadow_callback(Extension *extension, ExtensionEvent event,
			intptr_t data1, intptr_t data2)
{
	switch (event) {
	case INITIALIZATION: 
		return handle_init(extension);

	case SYSCALL_ENTER_END:
		return handle_sysenter_end(TRACEE(extension));

	case SYSCALL_EXIT_END:
		return handle_sysexit_end(TRACEE(extension));

	case EXEC_PATH:
		return handle_exec_path(TRACEE(extension), (char *) data1);

	case NEW_STATUS:
		return handle_new_status(TRACEE(extension), (int) data1);
	
	case LINK2SYMLINK_RENAME:
		return handle_rename_core(TRACEE(extension), (char *) data1, (char *) data2);

	case LINK2SYMLINK_UNLINK:
		return handle_unlink_core(TRACEE(extension), (char *) data1);

	default:
		return 0;
	}
}

//TODO: detect why hitting ctrl+c while cat is waiting for input doesn't push cat back to shadow
//might need to handle kill syscall
//handle_new_status might not be necessary
//TODO: handle O_CLOEXEC
