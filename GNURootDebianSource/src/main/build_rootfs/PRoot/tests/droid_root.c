#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>

int main(int argc, char *argv[])
{
	int status;
	int fd;
	unlink("/foo");
	unlink("/foo.new");
	fd=open("/foo.new", O_CREAT | O_WRONLY, 0777);
	//fchown(fd,0,0);
	fchmod(fd,0555);
	//rename("/foo.new", "/foo");
}
