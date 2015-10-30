#include <errno.h>


extern int audit_open (void);
int audit_open (void)
{
	errno = EPROTONOSUPPORT;
	return -1;
}

extern int is_selinux_enabled (void);
int is_selinux_enabled (void)
{
	return 0;
}

extern int setexecfilecon (const char *filename, const char *fallback_type);
int setexecfilecon (const char *filename, const char *fallback_type)
{
	return 0;
}

extern int setexeccon(const char * con);
int setexeccon(const char * con)
{
	return 0;
}

extern int setfilecon(const char *path, const char * con);
int setfilecon(const char *path, const char * con)
{
	return 0;
}
