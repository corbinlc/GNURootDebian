/* -*- c-set-style: "K&R"; c-basic-offset: 8 -*-
 *
 * This file is part of PRoot.
 *
 * Copyright (C) 2015 STMicroelectronics
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

#include <assert.h>      /* assert(3), */
#include <stdint.h>      /* intptr_t, */
#include <errno.h>       /* E*, */
#include <sys/stat.h>    /* chmod(2), stat(2) */
#include <sys/types.h>   /* uid_t, gid_t, get*id(2), */
#include <unistd.h>      /* get*id(2),  */
#include <sys/ptrace.h>  /* linux.git:c0a3a20b  */
#include <linux/audit.h> /* AUDIT_ARCH_*,  */
#include <string.h>      /* memcpy(3), */
#include <stdio.h>       /* sprintf(3), */
#include <stdlib.h>      /* strtol(3), */
#include <linux/auxvec.h>/* AT_,  */
#include <grp.h>         /* getgrouplist(3) */
#include <pwd.h>        /* getpwuid(3) */
#include <fcntl.h>

#include "extension/extension.h"
#include "syscall/syscall.h"
#include "syscall/sysnum.h"
#include "syscall/seccomp.h"
#include "execve/execve.h"
#include "tracee/tracee.h"
#include "tracee/abi.h"
#include "tracee/mem.h"
#include "execve/auxv.h"
#include "path/binding.h"
#include "path/path.h"
#include "arch.h"

#define META_TAG        ".proot-meta-file."
#define META_LEN        17
#define IGNORE_SYSARG   2000
#define OWNER_PERMS     0
#define GROUP_PERMS     1
#define OTHER_PERMS     2

typedef struct {
    uid_t ruid;
    uid_t euid;
    uid_t suid;
    uid_t fsuid;

    gid_t rgid;
    gid_t egid;
    gid_t sgid;
    gid_t fsgid;
} Config;

typedef struct {
    char *path;
    mode_t mode;
} ModifiedNode;

/* List of syscalls handled by this extensions.  */
static FilteredSysnum filtered_sysnums[] = {
    { PR_access,        FILTER_SYSEXIT },
    { PR_capset,        FILTER_SYSEXIT },
    { PR_chmod,         FILTER_SYSEXIT },
    { PR_chown,         FILTER_SYSEXIT },
    { PR_chown32,       FILTER_SYSEXIT },
    { PR_chroot,        FILTER_SYSEXIT },
    { PR_creat,         FILTER_SYSEXIT },
    { PR_execve,        FILTER_SYSEXIT },
    { PR_faccessat,     FILTER_SYSEXIT },
    { PR_fchmod,        FILTER_SYSEXIT },
    { PR_fchmodat,      FILTER_SYSEXIT },
    { PR_fchown,        FILTER_SYSEXIT },
    { PR_fchown32,      FILTER_SYSEXIT },
    { PR_fchownat,      FILTER_SYSEXIT },
    { PR_fstat,         FILTER_SYSEXIT },
    { PR_fstat,         FILTER_SYSEXIT },
    { PR_fstat64,       FILTER_SYSEXIT },
    { PR_fstatat64,     FILTER_SYSEXIT },
    { PR_getegid,       FILTER_SYSEXIT },
    { PR_getegid32,     FILTER_SYSEXIT },
    { PR_geteuid,       FILTER_SYSEXIT },
    { PR_geteuid32,     FILTER_SYSEXIT },
    { PR_getgid,        FILTER_SYSEXIT },
    { PR_getgid32,      FILTER_SYSEXIT },
    { PR_getgroups,     FILTER_SYSEXIT },
    { PR_getgroups32,   FILTER_SYSEXIT },
    { PR_getresgid,     FILTER_SYSEXIT },
    { PR_getresgid32,   FILTER_SYSEXIT },
    { PR_getresuid,     FILTER_SYSEXIT },
    { PR_getresuid32,   FILTER_SYSEXIT },
    { PR_getuid,        FILTER_SYSEXIT },
    { PR_getuid32,      FILTER_SYSEXIT },
    { PR_lchown,        FILTER_SYSEXIT },
    { PR_lchown32,      FILTER_SYSEXIT },
    { PR_lstat,         FILTER_SYSEXIT },
    { PR_lstat64,       FILTER_SYSEXIT },
    { PR_mkdir,         FILTER_SYSEXIT },
    { PR_mkdirat,       FILTER_SYSEXIT },
    { PR_mknod,         FILTER_SYSEXIT },
    { PR_mknodat,       FILTER_SYSEXIT },
    { PR_newfstatat,    FILTER_SYSEXIT },
    { PR_oldlstat,      FILTER_SYSEXIT },
    { PR_oldstat,       FILTER_SYSEXIT },
    { PR_open,          FILTER_SYSEXIT },
    { PR_openat,        FILTER_SYSEXIT },
    { PR_rename,        FILTER_SYSEXIT },
    { PR_renameat,      FILTER_SYSEXIT },
    { PR_rmdir,         FILTER_SYSEXIT },
    { PR_setfsgid,      FILTER_SYSEXIT },
    { PR_setfsgid32,    FILTER_SYSEXIT },
    { PR_setfsuid,      FILTER_SYSEXIT },
    { PR_setfsuid32,    FILTER_SYSEXIT },
    { PR_setgid,        FILTER_SYSEXIT },
    { PR_setgid32,      FILTER_SYSEXIT },
    { PR_setgroups,     FILTER_SYSEXIT },
    { PR_setgroups32,   FILTER_SYSEXIT },
    { PR_setregid,      FILTER_SYSEXIT },
    { PR_setregid32,    FILTER_SYSEXIT },
    { PR_setreuid,      FILTER_SYSEXIT },
    { PR_setreuid32,    FILTER_SYSEXIT },
    { PR_setresgid,     FILTER_SYSEXIT },
    { PR_setresgid32,   FILTER_SYSEXIT },
    { PR_setresuid,     FILTER_SYSEXIT },
    { PR_setresuid32,   FILTER_SYSEXIT },
    { PR_setuid,        FILTER_SYSEXIT },
    { PR_setuid32,      FILTER_SYSEXIT },
    { PR_setxattr,      FILTER_SYSEXIT },
    { PR_setdomainname, FILTER_SYSEXIT },
    { PR_sethostname,   FILTER_SYSEXIT },
    { PR_lsetxattr,     FILTER_SYSEXIT },
    { PR_fsetxattr,     FILTER_SYSEXIT },
    { PR_stat,          FILTER_SYSEXIT },
    { PR_stat64,        FILTER_SYSEXIT },
    { PR_statfs,        FILTER_SYSEXIT },
    { PR_statfs64,      FILTER_SYSEXIT }, 
    { PR_unlink,        FILTER_SYSEXIT },
    { PR_unlinkat,      FILTER_SYSEXIT },
    { PR_utimensat,     FILTER_SYSEXIT },
    FILTERED_SYSNUM_END,
};

/** Converts a decimal number to its octal representation. Used to convert
 *  system returned modes to a more common form for humans.
 */
int dtoo(int n) 
{
    int rem, i=1, octal=0;
    while (n!=0)
    {
        rem=n%8;
        n/=8;
        octal+=rem*i;
        i*=10;
    }
    return octal;
}

/** Converts an octal number to its decimal representation. Used to return to a
 *  more machine-usable form of mode from human-readable.
 */
int otod(int n)
{
    int decimal=0, i=0, rem;
    while (n!=0)
    {
        int j;
        int pow = 1;
        for(j = 0; j < i; j++)
            pow = pow * 8;
        rem = n%10;
        n/=10;
        decimal += rem*pow;
        ++i;
    }
    return decimal;
}

/** Gets the final component of a path.
 */
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

/** Gets a path without its final component.
 */
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

/** Stores in meta_path the contents of orig_path with the addition of META_TAG
 *  to the final component.
 */
static int get_meta_path(char orig_path[PATH_MAX], char meta_path[PATH_MAX]) 
{
    char *filename;
    char meta_tag[PATH_MAX];

    get_dir_path(orig_path, meta_path);

    strcpy(meta_tag, META_TAG);

    filename = get_name(orig_path);
    strcat(meta_tag, filename);

    // This avoids situations like //.proot-meta-file.etc.
    if(strcmp(meta_path, "/") != 0)
        strcat(meta_path, "/");

    if(strlen(meta_path) + META_LEN >= PATH_MAX)
        return -ENAMETOOLONG;

    strcat(meta_path, meta_tag);
    return 0;
}

/** Gets a path from file descriptor system argument. If that sysarg is 
 *  IGNORE_FLAGS, it returns the root of the guestfs, and if the file 
 *  descriptor refers to the cwd, it returns that. Returning the root
 *  is used in cases where the function is used to find relative paths
 *  for __at calls.
 */
static int get_fd_path(Tracee *tracee, char path[PATH_MAX], Reg fd_sysarg)
{
    int status;
    if(fd_sysarg != IGNORE_SYSARG) {
        if( (signed int)peek_reg(tracee, CURRENT, fd_sysarg) == -100)
            status = getcwd2(tracee, path);
        else
            status = readlink_proc_pid_fd(tracee->pid, peek_reg(tracee, CURRENT, fd_sysarg), path);

        if(status < 0) 
            return status;
    }
    else
        strcpy(path, "/");

    /** If a path does not belong to the guestfs, a handler either exits with 0
     *  or sets the syscall to void (in the case of chmod and chown.
     */
    if(!belongs_to_guestfs(tracee, path))
        return 1;

    return 0;
}

/** Reads a path from path_sysarg into path.
 */
static int read_sysarg_path(Tracee *tracee, char path[PATH_MAX], Reg path_sysarg)
{
    int size;
    size = read_string(tracee, path, peek_reg(tracee, CURRENT, path_sysarg), PATH_MAX);
    if(size < 0) 
        return size;
    if(size >= PATH_MAX) 
        return -ENAMETOOLONG;

    /** If a path does not belong to the guestfs, a handler either exits with 0
     *  or sets the syscall to void (in the case of chmod and chown.
     */
    if(!belongs_to_guestfs(tracee, path))
        return 1;

    return 0;
}

/** Writes mode, owner, and group to the meta file specified by path. If 
 *  is_creat is set to true, the umask needs to be used since it would have
 *  been by a real system call.
 */
static int write_meta_file(char path[PATH_MAX], mode_t mode, uid_t owner, gid_t group, bool is_creat)
{
    mode_t um;
    FILE *fp;
    fp = fopen(path, "w");
    if(!fp) 
        //Errno is set
        return -1;

    /** In syscalls that don't have the ability to create a file (chmod v open)
     *  for example, the umask isn't used in determining the permissions of the
     *  the file.
     */
    if(is_creat) {
        /** The first call sets um to current umask value, and current umask value
         *  to 0. The second call resets the umask value to it's original value.
         */
        um = umask(0);
        umask(um);
        mode = mode & ~(um);
    }
    fprintf(fp, "%d\n%d\n%d\n", dtoo(mode), owner, group);
    fclose(fp);
    return 0; 
}

/** Stores in mode, owner, and group the relative information found in the meta
 *  meta file. If the meta file doesn't exist, it reverts back to the original
 *  functionality of PRoot, with the addition of setting the mode to 755.
 */
static int read_meta_file(char path[PATH_MAX], mode_t *mode, uid_t *owner, gid_t *group, const Config *config)
{
    FILE *fp;
    fp = fopen(path, "r");
    if(!fp) { 
        /* If the metafile doesn't exist, allow overly permissive behavior. */
        *owner = config->euid;
        *group = config->egid;
        *mode = otod(755);
        return 0;

    }
    fscanf(fp, "%d %d %d ", mode, owner, group);
    *mode = otod(*mode);
    fclose(fp);
    return 0;
}

/** Determines whether the file specified by path exists.
 */
static int path_exists(char path[PATH_MAX])
{
    return access(path, F_OK);  
}

/** Returns the mode pertinent to the level of permissions the user has. Eg if
 *  uid 1000 tries to access a file it owns with mode 751, this returns 7.
 */
static int get_permissions(char meta_path[PATH_MAX], const Config *config)
{
    int perms;
    int omode;
    mode_t mode;
    uid_t owner;
    gid_t group;

    int status = read_meta_file(meta_path, &mode, &owner, &group, config);
    if(status < 0) 
        return status;

    if (config->euid == owner || config->euid == 0)
        perms = OWNER_PERMS;
    else if(config->egid == group)
        perms = GROUP_PERMS;
    else
        perms = OTHER_PERMS;

    omode = dtoo(mode);
    switch(perms) {
    case OWNER_PERMS: 
        omode /= 10;
    case GROUP_PERMS:
        omode /= 10;
    case OTHER_PERMS:
        omode = omode % 10;        
    }

    return omode;
}

/** Checks permissions on every component of path. Up to the location specifed
 *  by rel_path. If type is specified to be "read", it checks only execute 
 *  permissions. If type is specified to be "write, it makes sure that the 
 *  parent directory of the file specified by path also has write permissions.
 */
static int check_dir_perms(char type, char path[PATH_MAX], char rel_path[PATH_MAX], const Config *config)
{
    int status, perms;
    char meta_path[PATH_MAX];
    char shorten_path[PATH_MAX];
    int x = 1; 
    int w = 2;

    get_dir_path(path, shorten_path);
    status = get_meta_path(shorten_path, meta_path); 
    if(status < 0)
        return status;

    perms = get_permissions(meta_path, config);

    if(type == 'w') {
        if((perms & w) == w) 
            status = 0;
        else 
            return -EACCES;
    }
    
    if(type == 'r') {
        if((perms & x) == x) 
            status = 0;
        else 
            return -EACCES;
    }

    while(strcmp(shorten_path, rel_path) != 0 && strlen(rel_path) < strlen(shorten_path)) {
        get_dir_path(shorten_path, shorten_path);
        status = get_meta_path(shorten_path, meta_path);
        if(status < 0)
            return status;

        perms = get_permissions(meta_path, config);
        if((perms & x) == x) 
            status = 0;
        else 
            return -EACCES;
        
    }

    return status;
}

/** Handles open, openat, and creat syscalls. Creates meta files to match the
 *  creation of new files, or checks the permissions of files that already
 *  exist given a matching meta file. See open(2) for returned permission
 *  errors.
 */
static int handle_open(Tracee *tracee, Reg fd_sysarg, Reg path_sysarg, 
    Reg flags_sysarg, Reg mode_sysarg, const Config *config)
{   
    int status, perms, access_mode;
    char orig_path[PATH_MAX];
    char rel_path[PATH_MAX];
    char meta_path[PATH_MAX];
    word_t flags;
    mode_t mode;
   
    status = read_sysarg_path(tracee, orig_path, path_sysarg); 
    if(status < 0) 
        return status;
    if(status == 1)
        return 0;

    status = get_meta_path(orig_path, meta_path);
    if(status < 0)
        return status;
 
    if(flags_sysarg != IGNORE_SYSARG) 
        flags = peek_reg(tracee, ORIGINAL, flags_sysarg);
    else  
        flags = 0;

    /* If the metafile doesn't exist and we aren't creating a new file, get out. */
    if(path_exists(meta_path) < 0 && (flags & O_CREAT) != O_CREAT)
        return 0;

    status = get_fd_path(tracee, rel_path, fd_sysarg);
    if(status < 0) 
        return status; 
    
    /** If the open call is a creat call (flags is set to IGNORE_SYSARG in 
     *  handle_sysenter_end) or an open call intended to create a new file
     *  then we write a new meta file to match. Note that flags is compared
     *  only to O_CREAT because some utilities (like touch) do not
     *  use O_TRUNC and O_WRONLY and instead incorporate other flags.
     *  A value in flags_sysarg of IGNORE_SYSARG signifies a creat(2) call.
     */
    if((flags & O_CREAT) == O_CREAT || flags_sysarg == IGNORE_SYSARG) { 
        /** To preserve original functionality, we don't create meta files for
         *  files that already exist. System calls have a tendency to include
         *  the O_CREAT flag even when the file already exists, so a check is
         *  necessary. 
         */
        if(path_exists(orig_path) == 0)
            return 0;

        status = check_dir_perms('w', meta_path, rel_path, config);
        if(status < 0) 
            return status;

        mode = peek_reg(tracee, ORIGINAL, mode_sysarg);
        poke_reg(tracee, mode_sysarg, otod(700));
        return write_meta_file(meta_path, mode, config->euid, config->egid, 1);
    }

    else { 
        status = check_dir_perms('r', meta_path, rel_path, config);
        if(status < 0) 
            return status;
        
        perms = get_permissions(meta_path, config); 
        access_mode = (flags & O_ACCMODE);

        if((access_mode == O_WRONLY && (perms & 2) != 2) ||
        (access_mode == O_RDONLY && (perms & 4) != 4) ||
        (access_mode == O_RDWR && (perms & 6) != 6)) {
            return -EACCES;
        }
    }

    return 0;
}

/** Handles mkdir, mkdirat, mknod, and mknodat syscalls. Creates a matching
 *  meta file. See mkdir(2) and mknod(2) for returned permission errors.
 */
static int handle_mk(Tracee *tracee, Reg fd_sysarg, Reg path_sysarg, 
    Reg mode_sysarg, const Config *config)
{
    int status;
    mode_t mode;
    char orig_path[PATH_MAX];
    char rel_path[PATH_MAX];
    char meta_path[PATH_MAX];

    status  = read_sysarg_path(tracee, orig_path, path_sysarg);
    if(status < 0)
        return status;
    if(status == 1)
        return 0;

    status = get_meta_path(orig_path, meta_path);
    if(status < 0)
        return status;

    status = get_fd_path(tracee, rel_path, fd_sysarg);
    if(status < 0) 
        return status;
    
    status = check_dir_perms('w', orig_path, rel_path, config);
    if(status < 0) 
        return status;
    
    mode = peek_reg(tracee, ORIGINAL, mode_sysarg);

    status = write_meta_file(meta_path, mode, config->euid, config->egid, 1);
    if(status < 0)
        return status;

    return 0;
}

/** Handles unlink, unlinkat, and rmdir syscalls. Checks permissions in meta 
 *  files matching the file to be unlinked if the meta file exists. Unlinks
 *  the meta file if the call would be successful. See unlink(2) and rmdir(2)
 *  for returned errors.
 */
static int handle_unlink(Tracee *tracee, Reg fd_sysarg, Reg path_sysarg, const Config *config)
{
    int status;
    char orig_path[PATH_MAX];
    char rel_path[PATH_MAX];
    char meta_path[PATH_MAX];
    
    status = read_sysarg_path(tracee, orig_path, path_sysarg); 
    if(status < 0) 
        return status;
    if(status == 1)
        return 0;

    status = get_meta_path(orig_path, meta_path);
    if(status < 0) 
        return status;
    
    status = get_fd_path(tracee, rel_path, fd_sysarg);
    if(status < 0) 
        return status;
    
    status = check_dir_perms('w', orig_path, rel_path, config);
    if(status < 0) 
        return status;
 
    /** If the meta_file relating to the file being unlinked exists,
     *  unlink that as well.
     */   
    status = path_exists(meta_path);
    if(status == 0) 
        unlink(meta_path);

    return 0;
}

/** Handles rename and renameat syscalls. If a meta file matching the file to
 *  to be renamed exists, renames the meta file as well. See rename(2) for
 *  returned permission errors.
 */
static int handle_rename(Tracee *tracee, Reg oldfd_sysarg, Reg oldpath_sysarg, 
    Reg newfd_sysarg, Reg newpath_sysarg, const Config *config)
{
    int status;
    uid_t uid;
    gid_t gid;
    mode_t mode;
    char oldpath[PATH_MAX];
    char newpath[PATH_MAX];
    char rel_oldpath[PATH_MAX];
    char rel_newpath[PATH_MAX];
    char meta_path[PATH_MAX];

    status = read_sysarg_path(tracee, oldpath, oldpath_sysarg); 
    if(status < 0)
        return status;
    if(status == 1)
        return 0;

    status = read_sysarg_path(tracee, newpath, newpath_sysarg); 
    if(status < 0)
        return status;
    if(status == 1)
        return 0;

    status = get_fd_path(tracee, rel_oldpath, oldfd_sysarg);
    if(status < 0)
        return status;

    status = get_fd_path(tracee, rel_newpath, newfd_sysarg);
    if(status < 0)
        return status;

    status = check_dir_perms('w', oldpath, rel_oldpath, config);
    if(status < 0)
        return status;

    status = check_dir_perms('w', newpath, rel_newpath, config);
    if(status < 0)
        return status;
   
    // "Copy" the old meta_file to a new one relating to the new path.
    status = get_meta_path(oldpath, meta_path);
    if(status < 0)
        return status;

    read_meta_file(meta_path, &mode, &uid, &gid, config);
    unlink(meta_path);
    
    strcpy(meta_path, "");
    status = get_meta_path(newpath, meta_path);
    if(status < 0)
        return status;

    write_meta_file(meta_path, mode, uid, gid, 0); 

    return 0;
}

/** Handles chmod, fchmod, and fchmodat syscalls. Changes meta files to the new
 *  permissions if the meta file exists. See chmod(2) for returned permission
 *  errors. 
 */
static int handle_chmod(Tracee *tracee, Reg path_sysarg, Reg mode_sysarg, 
    Reg fd_sysarg, Reg dirfd_sysarg, const Config *config)
{
    int status;
    mode_t call_mode, read_mode;
    uid_t owner;
    gid_t group;
    char path[PATH_MAX];
    char rel_path[PATH_MAX];
    char meta_path[PATH_MAX];

    // When path_sysarg is set to IGNORE, the call being handled is fchmod.
    if(path_sysarg == IGNORE_SYSARG) 
        status = get_fd_path(tracee, path, fd_sysarg);
    else
        status = read_sysarg_path(tracee, path, path_sysarg);
    if(status < 0)
        return status;
    // If the file exists outside the guestfs, drop the syscall.
    else if(status == 1) {
        poke_reg(tracee, SYSARG_RESULT, 0);
        set_sysnum(tracee, PR_void);
		return 0;
    }

    status = get_fd_path(tracee, rel_path, dirfd_sysarg);
    if(status < 0)
        return status;

    status = check_dir_perms('r', path, rel_path, config);
    if(status < 0) {
        return status;
    }
    
    status = get_meta_path(path, meta_path);
    if(status < 0)
        return status;


    read_meta_file(meta_path, &read_mode, &owner, &group, config);
    if(config->euid != owner && config->euid != 0) 
        return -EPERM;

    call_mode = peek_reg(tracee, ORIGINAL, mode_sysarg);
    write_meta_file(meta_path, call_mode, owner, group, 0);
    set_sysnum(tracee, PR_void);
    
    return 0;
}

/** Handles chown, lchown, fchown, and fchownat syscalls. Changes the meta file
 *  to reflect arguments sent to the syscall if the meta file exists. See
 *  chown(2) for returned permission errors.
 */
static int handle_chown(Tracee *tracee, Reg path_sysarg, Reg owner_sysarg,
    Reg group_sysarg, Reg fd_sysarg, Reg dirfd_sysarg, const Config *config)
{
    int status;
    mode_t mode;
    uid_t owner, read_owner;
    gid_t group, read_group;
    char path[PATH_MAX];
    char rel_path[PATH_MAX];
    char meta_path[PATH_MAX];
    
    if(path_sysarg == IGNORE_SYSARG)
        status = get_fd_path(tracee, path, fd_sysarg);
    else
        status = read_sysarg_path(tracee, path, path_sysarg);
    if(status < 0)
        return status;
    // If the path exists outside the guestfs, drop the syscall.
    else if(status == 1) {
        poke_reg(tracee, SYSARG_RESULT, 0);
        set_sysnum(tracee, PR_void);
		return 0;
    }

    status = get_fd_path(tracee, rel_path, dirfd_sysarg);
    if(status < 0)
        return status;

    status = check_dir_perms('r', path, rel_path, config);
    if(status < 0)
        return status;

    status = get_meta_path(path, meta_path);
    if(status < 0)
        return status;

    read_meta_file(meta_path, &mode, &read_owner, &read_group, config);
    owner = peek_reg(tracee, ORIGINAL, owner_sysarg);
    /** When chown is called without an owner specified, eg 
     *  chown :1000 'file', the owner argument to the system call is implicitly
     *  set to -1. To avoid this, the owner argument is replaced with the owner
     *  according to the meta file if it exists, or the current euid.
     */
    if((int) owner == -1)
        owner = read_owner;
    group = peek_reg(tracee, ORIGINAL, group_sysarg);
    if(config->euid == 0) 
        write_meta_file(meta_path, mode, owner, group, 0);
    
    
    //TODO Handle chown properly: owner can only change the group of
    //  a file to another group they belong to.
    else if(config->euid == read_owner) {
        write_meta_file(meta_path, mode, read_owner, group, 0);
        poke_reg(tracee, owner_sysarg, read_owner);    
    }
    else if(config->euid != read_owner)
        return -EPERM;

    set_sysnum(tracee, PR_void);

    return 0;
}

/** Handles the utimensat syscall. Checks permissions of the meta file if it
 *  exists and returns an error if the call would not pass according to the 
 *  errors found in utimensat(2).
 */
static int handle_utimensat(Tracee *tracee, Reg dirfd_sysarg, 
    Reg path_sysarg, Reg times_sysarg, const Config *config)
{
    int status, perms, fd;
    struct timespec times[2];
    mode_t ignore_m;
    uid_t owner;
    gid_t ignore_g;
    char path[PATH_MAX];
    char meta_path[PATH_MAX];

    // Only care about calls that attempt to change something.
    status = peek_reg(tracee, ORIGINAL, times_sysarg);
    if(status != 0) {
        status = read_data(tracee, times, peek_reg(tracee, ORIGINAL, times_sysarg), sizeof(times));
        if(times[0].tv_nsec != UTIME_NOW && times[1].tv_nsec != UTIME_NOW) 
            return 0;
    }

    fd = peek_reg(tracee, ORIGINAL, dirfd_sysarg);
    if(fd == AT_FDCWD) {
        status = read_sysarg_path(tracee, path, path_sysarg);
        if(status < 0) 
            return status;
        if(status == 1)
            return 0;
    }
    else {
        status = get_fd_path(tracee, path, dirfd_sysarg);
        if(status < 0)
            return status;
    }

    status = get_meta_path(path, meta_path);
    if(status < 0)
        return status;

    // Current user must be owner of file or root.
    read_meta_file(meta_path, &ignore_m, &owner, &ignore_g, config);
    if(config->euid != owner && config->euid != 0) {
        return -EACCES;
    }

    // If write permissions are on the file, continue.
    perms = get_permissions(meta_path, config);
    if((perms & 2) != 2)
        return -EACCES;

    return 0;
}

/** Handles the access and faccessat syscalls. Checks permissions according to
 *  a meta file if it exists. See access(2) for returned errors.
 */
static int handle_access(Tracee *tracee, Reg path_sysarg,
    Reg mode_sysarg, Reg dirfd_sysarg, const Config *config)
{
    int status, mode, perms, mask;
    char path[PATH_MAX];
    char rel_path[PATH_MAX];
    char meta_path[PATH_MAX];

    status = read_sysarg_path(tracee, path, path_sysarg);
    if(status < 0)
        return status;
    if(status == 1)
        return 0;

    status = get_fd_path(tracee, rel_path, dirfd_sysarg);
    if(status < 0)
        return status;

    status = check_dir_perms('r', path, rel_path, config);
    if(status < 0)
        return status;

    mode = peek_reg(tracee, ORIGINAL, mode_sysarg);
    if(mode & F_OK) {
        status = path_exists(path);
        return status;
    }

    mask = 0;
    if((mode & R_OK) == R_OK)
        mask += 4;
    if((mode & W_OK) == W_OK)
        mask += 2;
    if((mode & X_OK) == X_OK)
        mask += 1; 
    
    status = get_meta_path(path, meta_path);
    if(status < 0)
        return status;

    perms = get_permissions(meta_path, config);
    if((perms & mask) != mask)
        return -EACCES;

    return 0;
}

/** Handles execve system calls. Checks permissions in a meta file if it exists
 *  and returns errors matching those in execve(2).
 */
static int handle_exec(Tracee *tracee, Reg filename_sysarg, const Config *config)
{
    int status, perms;
    char path[PATH_MAX];
    char meta_path[PATH_MAX];

    status = read_sysarg_path(tracee, path, filename_sysarg);
    if(status < 0) 
        return status;
    if(status == 1)
        return 0;

    status = get_meta_path(path, meta_path);
    if(status < 0) 
        return status;
    

    /* If metafile doesn't exist, get out, but don't error. */
    status = path_exists(meta_path);
    if(status < 0) 
        return 0;
    
    /* Check perms relative to / since there is no dirfd argument to execve */
    status = check_dir_perms('r', meta_path, "/", config);
    if(status < 0) 
        return status;
    
    /* Check whether the file has execute permission. */
    perms = get_permissions(meta_path, config);
    if((perms & 1) != 1) 
        return -EACCES;

    /** TODO Add logic to determine interpreter being used, and check
     *  permissions for it.
     */

    return 0;
}

/** Handles link and linkat. Returns -EACCES if search permission is not
 *  given for the entire relative oldpath and the entire relative newpath
 *  except where write permission is needed (on the final directory component).
 */
static int handle_link(Tracee *tracee, Reg olddirfd_sysarg, Reg oldpath_sysarg,
    Reg newdirfd_sysarg, Reg newpath_sysarg, const Config *config)
{
    int status;
    char oldpath[PATH_MAX];
    char rel_oldpath[PATH_MAX];
    char newpath[PATH_MAX];
    char rel_newpath[PATH_MAX];

    status = read_sysarg_path(tracee, oldpath, oldpath_sysarg);
    if(status < 0)
        return status;
    if(status == 1)
        return 0;

    status = read_sysarg_path(tracee, newpath, newpath_sysarg);
    if(status < 0)
        return status;
    if(status == 1)
        return 0;

    status = get_fd_path(tracee, rel_oldpath, olddirfd_sysarg);
    if(status < 0)
        return status;

    status = get_fd_path(tracee, rel_newpath, newdirfd_sysarg);
    if(status < 0)
        return status;

    status = check_dir_perms('r', oldpath, rel_oldpath, config);
    if(status < 0)
        return status;

    status = check_dir_perms('w', newpath, rel_newpath, config);
    if(status < 0)
        return status;

    return 0;
}

/** Handles symlink and symlinkat syscalls. Returns -EACCES if search
 *  permission is not found for the directories except the final in newpath.
 *  Write permission is required for that directory. Unlike with link(2), 
 *  symlink does not require permissions on the original path.
 */
static int handle_symlink(Tracee *tracee, Reg oldpath_sysarg,
    Reg newdirfd_sysarg, Reg newpath_sysarg, const Config *config)
{
    int status;
    char oldpath[PATH_MAX];
    char newpath[PATH_MAX];
    char rel_newpath[PATH_MAX];

    status = read_sysarg_path(tracee, oldpath, oldpath_sysarg);
    if(status < 0)
        return status;
    if(status == 1)
        return 0;

    status = read_sysarg_path(tracee, newpath, newpath_sysarg);
    if(status < 0)
        return status;
    if(status == 1)
        return 0;

    status = get_fd_path(tracee, rel_newpath, newdirfd_sysarg);
    if(status < 0)
        return status;

    status = check_dir_perms('w', newpath, rel_newpath, config);
    if(status < 0)
        return status;

    return 0;
}

/**
 * Restore the @node->mode for the given @node->path.
 *
 * Note: this is a Talloc destructor.
 */
static int restore_mode(ModifiedNode *node)
{
    (void) chmod(node->path, node->mode);
    return 0;
}

/**
 * Force permissions of @path to "rwx" during the path translation of
 * current @tracee's syscall, in order to simulate CAP_DAC_OVERRIDE.
 * The original permissions are restored through talloc destructors.
 * See canonicalize() for the meaning of @is_final.
 */
static void override_permissions(const Tracee *tracee, const char *path, bool is_final)
{
    ModifiedNode *node;
    struct stat perms;
    mode_t new_mode;
    int status;

    /* Get the meta-data */
    status = stat(path, &perms);
    if (status < 0)
        return;

    /* Copy the current permissions */
    new_mode = perms.st_mode & (S_IRWXU | S_IRWXG | S_IRWXO);

    /* Add read and write permissions to everything.  */
    new_mode |= (S_IRUSR | S_IWUSR);

    /* Always add 'x' bit to directories */
    if (S_ISDIR(perms.st_mode))
        new_mode |= S_IXUSR;

    /* Patch the permissions only if needed.  */
    if (new_mode == (perms.st_mode & (S_IRWXU | S_IRWXG | S_IRWXO)))
        return;

    node = talloc_zero(tracee->ctx, ModifiedNode);
    if (node == NULL)
        return;

    if (!is_final) {
        /* Restore the previous mode of any non final components.  */
        node->mode = perms.st_mode;
    }
    else {
        switch (get_sysnum(tracee, ORIGINAL)) {
        /* For chmod syscalls: restore the new mode of the final component.  */
        case PR_chmod:
            node->mode = peek_reg(tracee, ORIGINAL, SYSARG_2);
            break;

        case PR_fchmodat:
            node->mode = peek_reg(tracee, ORIGINAL, SYSARG_3);
            break;

        /* For stat syscalls: don't touch the mode of the final component.  */
        case PR_fstatat64:
        case PR_lstat:
        case PR_lstat64:
        case PR_newfstatat:
        case PR_oldlstat:
        case PR_oldstat:
        case PR_stat:
        case PR_stat64:
        case PR_statfs:
        case PR_statfs64:
            return;

        /* Otherwise: restore the previous mode of the final component.  */
        default:
            node->mode = perms.st_mode;
            break;
        }
    }

    node->path = talloc_strdup(node, path);
    if (node->path == NULL) {
        /* Keep only consistent nodes.  */
        TALLOC_FREE(node);
        return;
    }

    /* The mode restoration works because Talloc destructors are
     * called in reverse order.  */
    talloc_set_destructor(node, restore_mode);

    (void) chmod(path, new_mode);

    return;
}

/**
 * Adjust current @tracee's syscall parameters according to @config.
 * This function always returns 0.
 */
static int handle_sysenter_end(Tracee *tracee, const Config *config)
{
    word_t sysnum;
   
    sysnum = get_sysnum(tracee, ORIGINAL);
    switch (sysnum) {

    /* handle_open(tracee, fd_sysarg, path_sysarg, flags_sysarg, mode_sysarg, config) */
    /* int openat(int dirfd, const char *pathname, int flags, mode_t mode) */
    case PR_openat:
        return handle_open(tracee, SYSARG_1, SYSARG_2, SYSARG_3, SYSARG_4, config);
    /* int open(const char *pathname, int flags, mode_t mode) */
    case PR_open:
        return handle_open(tracee, IGNORE_SYSARG, SYSARG_1, SYSARG_2, SYSARG_3, config); 
    /* int creat(const char *pathname, mode_t mode) */
    case PR_creat:
        return handle_open(tracee, IGNORE_SYSARG, SYSARG_1, IGNORE_SYSARG, SYSARG_2, config);

    /* handle_mk(tracee, fd_sysarg, path_sysarg, mode_sysarg, config) */
    /* int mkdirat(int dirfd, const char *pathname, mode_t mode) */
    case PR_mkdirat:
       return handle_mk(tracee, SYSARG_1, SYSARG_2, SYSARG_3, config);
    /* int mkdir(const char *pathname, mode_t mode) */
    case PR_mkdir:
        return handle_mk(tracee, IGNORE_SYSARG, SYSARG_1, SYSARG_2, config); 


    /* handle_mk(tracee, fd_sysarg, path_sysarg, mode_sysarg, config) */
    /* int mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev); */
    case PR_mknodat:
        return handle_mk(tracee, SYSARG_1, SYSARG_2, SYSARG_3, config);
    /* int mknod(const char *pathname, mode_t mode, dev_t dev); */
    case PR_mknod:
        return handle_mk(tracee, IGNORE_SYSARG, SYSARG_1, SYSARG_2, config);

    /* handle_unlink(tracee, fd_sysarg, path_sysarg, config) */
    /* int unlinkat(int dirfd, const char *pathname, int flags) */
    case PR_unlinkat:
        return handle_unlink(tracee, SYSARG_1, SYSARG_2, config);
    /* int rmdir(const char *pathname */
    case PR_rmdir:
    /* int unlink(const char *pathname) */
    case PR_unlink:
        return handle_unlink(tracee, IGNORE_SYSARG, SYSARG_1, config);

    /* handle_rename(tracee, oldfd_sysarg, oldpath_sysarg, newfd_sysarg, newpath_sysarg, config) */
    /* int renameat(int olddirfd, const char *oldpath,
                    int newdirfd, const char *newpath) */
    case PR_renameat:
        return handle_rename(tracee, SYSARG_1, SYSARG_2, SYSARG_3, SYSARG_4, config);
    /* int rename(const char *oldpath, const char *newpath) */
    case PR_rename:
        return handle_rename(tracee, IGNORE_SYSARG, SYSARG_1, IGNORE_SYSARG, SYSARG_2, config);

    /* handle_chmod(tracee, path_sysarg, mode_sysarg, fd_sysarg, dirfd_sysarg, config) */
    /* int chmod(const char *pathname, mode_t mode) */
    case PR_chmod:
        return handle_chmod(tracee, SYSARG_1, SYSARG_2, 
            IGNORE_SYSARG, IGNORE_SYSARG, config);
    /* int fchmod(int fd, mode_t mode) */
    case PR_fchmod: 
        return handle_chmod(tracee, IGNORE_SYSARG, SYSARG_2, 
            SYSARG_1, IGNORE_SYSARG, config);
    /* int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags (unused)) */
    case PR_fchmodat:
        return handle_chmod(tracee, SYSARG_2, SYSARG_3, 
            IGNORE_SYSARG, SYSARG_1, config);

    /* handle_chown(tracee, path_sysarg, owner_sysarg, group_sysarg, fd_sysarg, dirfd_sysarg, config) */
    /* int chown(const char *pathname, uid_t owner, gid_t group) */
    case PR_chown:
    case PR_chown32:
        return handle_chown(tracee, SYSARG_1, SYSARG_2, SYSARG_3, 
            IGNORE_SYSARG, IGNORE_SYSARG, config);
    /* int fchown(int fd, uid_t owner, gid_t group) */
    case PR_fchown:
    case PR_fchown32:
        return handle_chown(tracee, IGNORE_SYSARG, SYSARG_2, SYSARG_3,
            SYSARG_1, IGNORE_SYSARG, config);
    /* int lchown(const char *pathname, uid_t owner, gid_t group) */
    case PR_lchown:
    case PR_lchown32:
        return handle_chown(tracee, SYSARG_1, SYSARG_2, SYSARG_3,
            IGNORE_SYSARG, IGNORE_SYSARG, config);
    /* int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags (unused)) */
    case PR_fchownat:
        return handle_chown(tracee, SYSARG_2, SYSARG_3, SYSARG_4,
            IGNORE_SYSARG, SYSARG_1, config);

    /* handle_utimensat(tracee, dirfd_sysarg, path_sysarg, times_sysarg, config) */
    /* int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags) */
    case PR_utimensat:
        return handle_utimensat(tracee, SYSARG_1, SYSARG_2, SYSARG_3, config);

    /* handle_access(tracee path_sysarg, mode_sysarg, dirfd_sysarg, config) */
    /* int access(const char *pathname, int mode) */
    case PR_access: 
        return handle_access(tracee, SYSARG_1, SYSARG_2, IGNORE_SYSARG, config);
    /* int faccessat(int dirfd, const char *pathname, int mode, int flags) */
    case PR_faccessat:
        return handle_access(tracee, SYSARG_2, SYSARG_3, SYSARG_1, config); 

    /* handle_exec(tracee, filename_sysarg, config) */
    case PR_execve:
        return handle_exec(tracee, SYSARG_1, config);

    /* handle_link(tracee, olddirfd_sysarg, oldpath_sysarg, newdirfd_sysarg, newpath_sysarg, config) */
    /* int link(const char *oldpath, const char *newpath) */
    case PR_link:
        return handle_link(tracee, IGNORE_SYSARG, SYSARG_1, IGNORE_SYSARG, SYSARG_2, config);
    /* int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) */
    case PR_linkat:
        return handle_link(tracee, SYSARG_1, SYSARG_2, SYSARG_3, SYSARG_4, config);
    /* int symlink(const char *target, const char *linkpath); */
    case PR_symlink:
        return handle_symlink(tracee, SYSARG_1, IGNORE_SYSARG, SYSARG_2, config);
    /* int symlinkat(const char *target, int newdirfd, const char *linkpath); */
    case PR_symlinkat:
        return handle_symlink(tracee, SYSARG_1, SYSARG_2, SYSARG_3, config);

    case PR_setuid:
    case PR_setuid32:
    case PR_setgid:
    case PR_setgid32:
    case PR_setreuid:
    case PR_setreuid32:
    case PR_setregid:
    case PR_setregid32:
    case PR_setresuid:
    case PR_setresuid32:
    case PR_setresgid:
    case PR_setresgid32:
    case PR_setfsuid:
    case PR_setfsuid32:
    case PR_setfsgid:
    case PR_setfsgid32:
        /* These syscalls are fully emulated.  */
        set_sysnum(tracee, PR_void);
        return 0;

    case PR_setgroups:
    case PR_setgroups32:
    case PR_getgroups:
    case PR_getgroups32:
        /* TODO */

    default:
        return 0;
    }

    /* Never reached  */
    assert(0);
    return 0;

}

/**
 * Copy config->@field to the tracee's memory location pointed to by @sysarg.
 */
#define POKE_MEM_ID(sysarg, field) do {                 \
    poke_uint16(tracee, peek_reg(tracee, ORIGINAL, sysarg), config->field); \
    if (errno != 0)                         \
        return -errno;                      \
} while (0)

/**
 * Emulate setuid(2) and setgid(2).
 */
#define SETXID(id) do {                         \
    id ## _t id = peek_reg(tracee, ORIGINAL, SYSARG_1);     \
    bool allowed;                           \
                                    \
    /* "EPERM: The user is not privileged (does not have the    \
     * CAP_SETUID capability) and uid does not match the real UID   \
     * or saved set-user-ID of the calling process." -- man     \
     * setuid */                            \
    allowed = (config->euid == 0 /* TODO: || HAS_CAP(SETUID) */ \
        || id == config->r ## id                \
        || id == config->e ## id                \
        || id == config->s ## id);              \
    if (!allowed)                            \
        return -EPERM;                      \
                                    \
    /* "If the effective UID of the caller is root, the real UID    \
     * and saved set-user-ID are also set." -- man setuid     \
     * The original PRoot checked config->e ## id here. Only euid should \
     *  be checked in order to emulate "real" functionality. \
     */ \
    if (config->euid == 0) {                 \
        config->r ## id = id;                   \
        config->s ## id = id;                   \
    }                               \
                                    \
    /* "whenever the effective user ID is changed, fsuid will also  \
     * be changed to the new value of the effective user ID."  --   \
     * man setfsuid */                      \
    config->e ## id  = id;                      \
    config->fs ## id = id;                      \
                                    \
    poke_reg(tracee, SYSARG_RESULT, 0);             \
    return 0;                           \
} while (0)

/**
 * Check whether @id is set or not.
 */
#define UNSET_ID(id) (id == (uid_t) -1)

/**
 * Check whether @id is change or not.
 */
#define UNCHANGED_ID(id) (UNSET_ID(id) || id == config->id)

/**
 * Emulate setreuid(2) and setregid(2).
 */
#define SETREXID(id) do {                       \
    id ## _t r ## id = peek_reg(tracee, ORIGINAL, SYSARG_1);    \
    id ## _t e ## id = peek_reg(tracee, ORIGINAL, SYSARG_2);    \
    bool allowed;                           \
                                    \
    /* "Unprivileged processes may only set the effective user ID   \
     * to the real user ID, the effective user ID, or the saved \
     * set-user-ID.                         \
     *                              \
     * Unprivileged users may only set the real user ID to the  \
     * real user ID or the effective user ID."          \
     *
     * "EPERM: The calling process is not privileged (does not  \
     * have the CAP_SETUID) and a change other than:        \
     * 1. swapping the effective user ID with the real user ID, \
     *    or;                           \
     * 2. setting one to the value of the other, or ;       \
     * 3. setting the effective user ID to the value of the saved   \
     *    set-user-ID                       \
     * was specified." -- man setreuid              \
     *                              \
     * Is it possible to "ruid <- euid" and "euid <- suid" at the   \
     * same time?  */                       \
    allowed = (config->euid == 0 /* TODO: || HAS_CAP(SETUID) */ \
        || (UNCHANGED_ID(e ## id) && UNCHANGED_ID(r ## id)) \
        || (r ## id == config->e ## id && (e ## id == config->r ## id || UNCHANGED_ID(e ## id))) \
        || (e ## id == config->r ## id && (r ## id == config->e ## id || UNCHANGED_ID(r ## id))) \
        || (e ## id == config->s ## id && UNCHANGED_ID(r ## id))); \
    if (!allowed)                           \
        return -EPERM;                      \
                                    \
    /* "Supplying a value of -1 for either the real or effective    \
     * user ID forces the system to leave that ID unchanged.    \
     * [...]  If the real user ID is set or the effective user ID   \
     * is set to a value not equal to the previous real user ID,    \
     * the saved set-user-ID will be set to the new effective user  \
     * ID." -- man setreuid */                  \
    if (!UNSET_ID(e ## id)) {                   \
        if (e ## id != config->r ## id)             \
            config->s ## id = e ## id;          \
                                    \
        config->e ## id  = e ## id;             \
        config->fs ## id = e ## id;             \
    }                               \
                                    \
    /* Since it changes the current ruid value, this has to be  \
     * done after euid handling. */                 \
    if (!UNSET_ID(r ## id)) {                   \
        if (!UNSET_ID(e ## id))                 \
            config->s ## id = e ## id;          \
        config->r ## id = r ## id;              \
    }                               \
                                    \
    poke_reg(tracee, SYSARG_RESULT, 0);             \
    return 0;                           \
} while (0)

/**
 * Check if @var is equal to any config->r{@type}id's.
 */
#define EQUALS_ANY_ID(var, type)  (var == config->r ## type ## id \
                || var == config->e ## type ## id \
                || var == config->s ## type ## id)

/**
 * Emulate setresuid(2) and setresgid(2).
 */
#define SETRESXID(type) do {                        \
    type ## id_t r ## type ## id = peek_reg(tracee, ORIGINAL, SYSARG_1);    \
    type ## id_t e ## type ## id = peek_reg(tracee, ORIGINAL, SYSARG_2);    \
    type ## id_t s ## type ## id = peek_reg(tracee, ORIGINAL, SYSARG_3);    \
    bool allowed;                           \
                                    \
    /* "Unprivileged user processes may change the real UID,    \
     * effective UID, and saved set-user-ID, each to one of: the    \
     * current real UID, the current effective UID or the current   \
     * saved set-user-ID.                       \
     *                              \
     * Privileged processes (on Linux, those having the CAP_SETUID  \
     * capability) may set the real UID, effective UID, and saved   \
     * set-user-ID to arbitrary values." -- man setresuid */    \
    allowed = (config->euid == 0 /* || HAS_CAP(SETUID) */       \
        || ((UNSET_ID(r ## type ## id) || EQUALS_ANY_ID(r ## type ## id, type)) \
         && (UNSET_ID(e ## type ## id) || EQUALS_ANY_ID(e ## type ## id, type)) \
         && (UNSET_ID(s ## type ## id) || EQUALS_ANY_ID(s ## type ## id, type)))); \
    if (!allowed)                           \
        return -EPERM;                      \
                                    \
    /* "If one of the arguments equals -1, the corresponding value  \
     * is not changed." -- man setresuid */             \
    if (!UNSET_ID(r ## type ## id))                 \
        config->r ## type ## id = r ## type ## id;      \
                                    \
    if (!UNSET_ID(e ## type ## id)) {               \
        /* "the file system UID is always set to the same   \
         * value as the (possibly new) effective UID." -- man   \
         * setresuid */                     \
        config->e ## type ## id  = e ## type ## id;     \
        config->fs ## type ## id = e ## type ## id;     \
    }                               \
                                    \
    if (!UNSET_ID(s ## type ## id))                 \
        config->s ## type ## id = s ## type ## id;      \
                                    \
    poke_reg(tracee, SYSARG_RESULT, 0);             \
    return 0;                           \
} while (0)

/**
 * Emulate setfsuid(2) and setfsgid(2).
 */
#define SETFSXID(type) do {                     \
    uid_t fs ## type ## id = peek_reg(tracee, ORIGINAL, SYSARG_1);  \
    uid_t old_fs ## type ## id = config->fs ## type ## id;      \
    bool allowed;                           \
                                    \
    /* "setfsuid() will succeed only if the caller is the       \
     * superuser or if fsuid matches either the real user ID,   \
     * effective user ID, saved set-user-ID, or the current value   \
     * of fsuid." -- man setfsuid */                \
    allowed = (config->euid == 0 /* TODO: || HAS_CAP(SETUID) */ \
        || fs ## type ## id == config->fs ## type ## id     \
        || EQUALS_ANY_ID(fs ## type ## id, type));      \
    if (allowed)                            \
        config->fs ## type ## id = fs ## type ## id;        \
                                    \
    /* "On success, the previous value of fsuid is returned.  On    \
     * error, the current value of fsuid is returned." -- man   \
     * setfsuid */                          \
    poke_reg(tracee, SYSARG_RESULT, old_fs ## type ## id);      \
    return 0;                           \
} while (0)

/**
 * Adjust current @tracee's syscall result according to @config.  This
 * function returns -errno if an error occured, otherwise 0.
 */
static int handle_sysexit_end(Tracee *tracee, Config *config)
{
    word_t sysnum;
    word_t result;

    sysnum = get_sysnum(tracee, ORIGINAL);
    switch (sysnum) {

    case PR_setuid:
    case PR_setuid32:
        SETXID(uid);

    case PR_setgid:
    case PR_setgid32:
        SETXID(gid);

    case PR_setreuid:
    case PR_setreuid32:
        SETREXID(uid);

    case PR_setregid:
    case PR_setregid32:
        SETREXID(gid);

    case PR_setresuid:
    case PR_setresuid32:
        SETRESXID(u);

    case PR_setresgid:
    case PR_setresgid32:
        SETRESXID(g);

    case PR_setfsuid:
    case PR_setfsuid32:
        SETFSXID(u);

    case PR_setfsgid:
    case PR_setfsgid32:
        SETFSXID(g);

    case PR_getuid:
    case PR_getuid32:
        poke_reg(tracee, SYSARG_RESULT, config->ruid);
        return 0;

    case PR_getgid:
    case PR_getgid32:
        poke_reg(tracee, SYSARG_RESULT, config->rgid);
        return 0;

    case PR_geteuid:
    case PR_geteuid32:
        poke_reg(tracee, SYSARG_RESULT, config->euid);
        return 0;

    case PR_getegid:
    case PR_getegid32:
        poke_reg(tracee, SYSARG_RESULT, config->egid);
        return 0;

    case PR_getresuid:
    case PR_getresuid32:
        POKE_MEM_ID(SYSARG_1, ruid);
        POKE_MEM_ID(SYSARG_2, euid);
        POKE_MEM_ID(SYSARG_3, suid);
        return 0;

    case PR_getresgid:
    case PR_getresgid32:
        POKE_MEM_ID(SYSARG_1, rgid);
        POKE_MEM_ID(SYSARG_2, egid);
        POKE_MEM_ID(SYSARG_3, sgid);
        return 0;

    case PR_setdomainname:
    case PR_sethostname:
    case PR_setgroups:
    case PR_setgroups32:
    case PR_mknod:
    case PR_mknodat:
    case PR_capset:
    case PR_setxattr:
    case PR_lsetxattr:
    case PR_fsetxattr:
    case PR_chmod:
    case PR_chown:
    case PR_fchmod:
    case PR_fchown:
    case PR_lchown:
    case PR_chown32:
    case PR_fchown32:
    case PR_lchown32:
    case PR_fchmodat:
    case PR_fchownat: {
        word_t result;

        result = peek_reg(tracee, CURRENT, SYSARG_RESULT);

        /** If the call has been set to PR_void, it "succeeded" in
         *  altering a meta file correctly.
         */ 
        if(get_sysnum(tracee, CURRENT) == PR_void && (int) result != -1) 
            poke_reg(tracee, SYSARG_RESULT, 0);
        
        /* Override only permission errors.  */
        if ((int) result != -EPERM)
            return 0;

        /* Force success if the tracee was supposed to have
         * the capability.  */
        if (config->euid == 0) /* TODO: || HAS_CAP(...) */
            poke_reg(tracee, SYSARG_RESULT, 0);

        return 0;
    }

    case PR_fstatat64:
    case PR_newfstatat:
    case PR_stat64:
    case PR_lstat64:
    case PR_fstat64:
    case PR_stat:
    case PR_lstat:
    case PR_fstat: {
        int status;
        word_t address;
        Reg sysarg;
        uid_t uid;
        gid_t gid;
        mode_t mode;
        struct stat my_stat;
        char path[PATH_MAX];
        char meta_path[PATH_MAX];

        /* Override only if it succeed.  */
        result = peek_reg(tracee, CURRENT, SYSARG_RESULT);
        if (result != 0)
            return 0;

        /* Get the pathname of the file to be 'stat'. */
        if(sysnum == PR_fstat) {
            status =get_fd_path(tracee, path, SYSARG_1);
        }
        else if(sysnum == PR_fstatat64 || sysnum == PR_newfstatat)
            status = read_sysarg_path(tracee, path, SYSARG_2);
        else
            status = read_sysarg_path(tracee, path, SYSARG_1);

        if(status < 0)
            return status;
        if(status == 1)
            return 0;
        
        /* Get the address of the 'stat' structure.  */
        if (sysnum == PR_fstatat64 || sysnum == PR_newfstatat)
            sysarg = SYSARG_3;
        else
            sysarg = SYSARG_2;

        /** If the meta file exists, read the data from it and replace it the
         *  relevant data in the stat structure.
         */
        
        status = get_meta_path(path, meta_path);
        if(status == 0) {
            status = path_exists(meta_path);
            if(status == 0) {
                read_meta_file(meta_path, &mode, &uid, &gid, config);
                /** TODO Could potentially just use the address, but would need
                 *  to figure out how to setup the offset for mode.
                 */  
                read_data(tracee, &my_stat, peek_reg(tracee, MODIFIED, sysarg), sizeof(struct stat));
                my_stat.st_mode = mode | (my_stat.st_mode & S_IFMT);
                my_stat.st_uid = uid;
                my_stat.st_gid = gid;
                write_data(tracee, peek_reg(tracee, MODIFIED, sysarg), &my_stat, sizeof(struct stat));
                return 0;
            }
        }

        address = peek_reg(tracee, ORIGINAL, sysarg);

        /* Sanity checks.  */
        assert(__builtin_types_compatible_p(uid_t, uint32_t));
        assert(__builtin_types_compatible_p(gid_t, uint32_t));

        /* Get the uid & gid values from the 'stat' structure.  */
        uid = peek_uint32(tracee, address + offsetof_stat_uid(tracee));
        if (errno != 0) 
            uid = 0; /* Not fatal.  */
        
        gid = peek_uint32(tracee, address + offsetof_stat_gid(tracee));
        if (errno != 0) 
            gid = 0; /* Not fatal.  */
        
        /* Override only if the file is owned by the current user.
         * Errors are not fatal here.  */
        if (uid == getuid()) 
            poke_uint32(tracee, address + offsetof_stat_uid(tracee), config->suid);
        
        if (gid == getgid()) 
            poke_uint32(tracee, address + offsetof_stat_gid(tracee), config->sgid);
        
        return 0;
    }

    case PR_chroot: {
        char path[PATH_MAX];
        word_t input;
        int status;

        if (config->euid != 0) /* TODO: && !HAS_CAP(SYS_CHROOT) */
            return 0;

        /* Override only permission errors.  */
        result = peek_reg(tracee, CURRENT, SYSARG_RESULT);
        if ((int) result != -EPERM)
            return 0;

        input = peek_reg(tracee, MODIFIED, SYSARG_1);

        status = read_path(tracee, path, input);
        if (status < 0)
            return status;

        /* Only "new rootfs == current rootfs" is supported yet.  */
        status = compare_paths(get_root(tracee), path);
        if (status != PATHS_ARE_EQUAL)
            return 0;

        /* Force success.  */
        poke_reg(tracee, SYSARG_RESULT, 0);
        return 0;
    }

    /** Check to see if a meta was created for a file that no longer exists.
     *  If so, delete it.
     */
    case PR_open:
    case PR_openat:
    case PR_creat: {
        int status;
        Reg sysarg;
        char path[PATH_MAX];
        char meta_path[PATH_MAX];

        if(sysnum == PR_open || sysnum == PR_creat)
            sysarg = SYSARG_1;
        else
            sysarg = SYSARG_2;
        
        status = read_sysarg_path(tracee, path, sysarg);
        if(status < 0)
            return status;
        if(status == 1)
            return 0;

        /* If the file exists, it doesn't matter if a metafile exists. */
        if(path_exists(path) == 0)
            return 0; 

        status = get_meta_path(path, meta_path);
        if(status < 0)
            return status;

        /* If the metafile exists and the original file does not, delete it. */
        if(path_exists(meta_path) == 0) 
            unlink(meta_path);

        return 0;
    }    

    default:
        return 0;
    }
}

#undef POKE_MEM_ID
#undef SETXID
#undef UNSET_ID
#undef UNCHANGED_ID
#undef SETREXID
#undef EQUALS_ANY_ID
#undef SETRESXID
#undef SETFSXID

/**
 * Adjust some ELF auxiliary vectors.  This function assumes the
 * "argv, envp, auxv" stuff is pointed to by @tracee's stack pointer,
 * as expected right after a successful call to execve(2).
 */
static int adjust_elf_auxv(Tracee *tracee, Config *config)
{
    ElfAuxVector *vectors;
    ElfAuxVector *vector;
    word_t vectors_address;

    vectors_address = get_elf_aux_vectors_address(tracee);
    if (vectors_address == 0)
        return 0;

    vectors = fetch_elf_aux_vectors(tracee, vectors_address);
    if (vectors == NULL)
        return 0;

    for (vector = vectors; vector->type != AT_NULL; vector++) {
        switch (vector->type) {
        case AT_UID:
            vector->value = config->ruid;
            break;

        case AT_EUID:
            vector->value = config->euid;
            break;

        case AT_GID:
            vector->value = config->rgid;
            break;

        case AT_EGID:
            vector->value = config->egid;
            break;

        default:
            break;
        }
    }

    push_elf_aux_vectors(tracee, vectors, vectors_address);

    return 0;
}

/**
 * Handler for this @extension.  It is triggered each time an @event
 * occurred.  See ExtensionEvent for the meaning of @data1 and @data2.
 */
int fake_id0_callback(Extension *extension, ExtensionEvent event, intptr_t data1, intptr_t data2)
{
    switch (event) {
    case INITIALIZATION: {
        const char *uid_string = (const char *) data1;
        const char *gid_string;
        Config *config;
        int uid, gid;

        errno = 0;
        uid = strtol(uid_string, NULL, 10);
        if (errno != 0)
            uid = getuid();

        gid_string = strchr(uid_string, ':');
        if (gid_string == NULL) {
            errno = EINVAL;
        }
        else {
            errno = 0;
            gid = strtol(gid_string + 1, NULL, 10);
        }
        /* Fallback to the current gid if an error occured.  */
        if (errno != 0)
            gid = getgid();

        extension->config = talloc(extension, Config);
        if (extension->config == NULL)
            return -1;

        config = talloc_get_type_abort(extension->config, Config);
        config->ruid  = uid;
        config->euid  = uid;
        config->suid  = uid;
        config->fsuid = uid;
        config->rgid  = gid;
        config->egid  = gid;
        config->sgid  = gid;
        config->fsgid = gid;

        extension->filtered_sysnums = filtered_sysnums;
        return 0;
    }

    case INHERIT_PARENT: /* Inheritable for sub reconfiguration ...  */
        return 1;

    case INHERIT_CHILD: {
        /* Copy the parent configuration to the child.  The
         * structure should not be shared as uid/gid changes
         * in one process should not affect other processes.
         * This assertion is not true for POSIX threads
         * sharing the same group, however Linux threads never
         * share uid/gid information.  As a consequence, the
         * GlibC emulates the POSIX behavior on Linux by
         * sending a signal to all group threads to cause them
         * to invoke the system call too.  Finally, PRoot
         * doesn't have to worry about clone flags.
         */

        Extension *parent = (Extension *) data1;
        extension->config = talloc_zero(extension, Config);
        if (extension->config == NULL)
            return -1;

        memcpy(extension->config, parent->config, sizeof(Config));
        return 0;
    }

    case HOST_PATH: {
        Tracee *tracee = TRACEE(extension);
        Config *config = talloc_get_type_abort(extension->config, Config);

        /* Force permissions if the tracee was supposed to
         * have the capability.  */
        if (config->euid == 0) /* TODO: || HAS_CAP(DAC_OVERRIDE) */
            override_permissions(tracee, (char*) data1, (bool) data2);
        return 0;
    }

    /** LINK2SYMLINK is an extension intended to emulate hard links on
     *  platforms that do not have the capability to create them. In order to
     *  retain functionality of metafiles, it's necessary to move the metafile
     *  associated with the file being linked to the end of a symlink chain.
     */
    case LINK2SYMLINK_RENAME: {
        int status;
        char old_meta[PATH_MAX];
        char new_meta[PATH_MAX];
    
        status = get_meta_path((char *) data1, old_meta);
        if(status < 0)
            return status;

        /* If meta doesn't exist, get out. */
        if(path_exists(old_meta) != 0)
            return 0; 

        status = get_meta_path((char *) data2, new_meta);
        if(status < 0)
            return status;

        status = rename(old_meta, new_meta);
        if(status < 0)
            return status;

        return 0;
    }

    case LINK2SYMLINK_UNLINK: {
        int status;
        char meta_path[PATH_MAX];

        status = get_meta_path((char *) data1, meta_path);
        if(status < 0)
            return status;

        /* If metafile doesn't already exist, get out */
        if(path_exists(meta_path) != 0)
            return 0;

        status = unlink(meta_path);
        if(status < 0) 
            return status;

        return 0;
    }

    case SYSCALL_ENTER_END: {
        Tracee *tracee = TRACEE(extension);
        Config *config = talloc_get_type_abort(extension->config, Config);

        return handle_sysenter_end(tracee, config);
    }

    case SYSCALL_EXIT_END: {
        Tracee *tracee = TRACEE(extension);
        Config *config = talloc_get_type_abort(extension->config, Config);

        return handle_sysexit_end(tracee, config);
    }

    case SYSCALL_EXIT_START: {
        Tracee *tracee = TRACEE(extension);
        Config *config = talloc_get_type_abort(extension->config, Config);
        word_t result = peek_reg(tracee, CURRENT, SYSARG_RESULT);
        word_t sysnum = get_sysnum(tracee, ORIGINAL);
        struct stat mode;
        int status;

        if ((int) result < 0 || sysnum != PR_execve)
            return 0;

        /* This has to be done before PRoot pushes the load
         * script into tracee's stack.  */
        adjust_elf_auxv(tracee, config);

        status = stat(tracee->load_info->host_path, &mode);
        if (status < 0)
            return 0; /* Not fatal.  */

        if ((mode.st_mode & S_ISUID) != 0) {
            config->euid = 0;
            config->suid = 0;
        }

        if ((mode.st_mode & S_ISGID) != 0) {
            config->egid = 0;
            config->sgid = 0;
        }

        return 0;
    }

    default:
        return 0;
    }
}
