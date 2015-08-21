#ifndef FUSER_H
#define FUSER_H

extern char **used_paths;
extern int num_used_paths;
extern char **unused_paths;
extern int num_unused_paths;
extern char mapped_file[PATH_MAX];
extern int mapped_file_valid;

extern int is_unused(char path[PATH_MAX]);
extern int update_used_paths(pid_t proc);
extern int update_unused_paths();
int update_mapped_file(pid_t proc, void *address);

#endif /*FUSER_H*/
