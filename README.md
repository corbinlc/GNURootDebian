# GNURootDebian
This is the repository corresponding to the GNURoot Debian Android app.

This can be built using the Android Studio 1.4 Beta 3, or any newer version that
includes support for both the NDK and experimental gradle plugin 0.2.0. This
includes the most recent version of Android Studio, version 2.1.2.

## PRoot
PRoot is an open source project that can be found here at
https://github.com/proot-me/PRoot

PRoot has been extended for GNURoot Debian to provide emulated Linux functionality
on the Android operating system.

## The Build Process
There are 3 essential components to build  GNURoot Debian. They are PRoot, a
rootfs.obb tar file, and GNURootDebian itself.

Unless changes are made to either PRoot or the rootfs that GNURoot Debian will use,
skip to the Build GNURootDebian header.

If changes are made to these first 2 components, note that they can be built
with the createReleaseRootfs.sh script located in
[GNURootDebian/GnuRootDebianSource/src/main/build\_rootfs/](https://github.com/corbinlc/GNURootDebian/tree/master/GNURootDebianSource/src/main/build_rootfs)

Using this script is the recommended method of building PRoot regardless of
desired operating system and the recommend method of building the obb if you
plan on using the jessie release of Debian.

Running this script requires the desired architecture for the bootstrap as the
first argument and the desired location of the bootstrap as the second. It also
requires that a version of PRoot and the disableselinux.c file be
located in the same directory.

EG
> ./createReleaseRootfs armhf /PATH\_TO\_BOOTSTRAP

### Build a bootstrap
*Note: GNURoot by default supports only armhf, armel, and i386 architectures.*

Suggested method of creating an i386 bootstrap while running an x86-64 architecture:
> debootstrap --arch=i386 --variant=minbase --include=sudo,dropbear jessie $2 http://ftp.debian.org/debian

Suggested method of creating a bootstrap otherwise:
> qemu-debootstrap --arch=$1 --variant=minbase --include=sudo,dropbear jessie $2 http://ftp.debian.org/debian

Where $2 is the pathname that will be the root of the bootstrap and $1 is the
desired architecture.

*Note: Other operating systems may require different bootstrap creation methods.*

### Build PRoot
*Note: This requires the user to be privileged.*
*Note: If running on the architecture you are building for, simply make PRoot
on that architecture.*

**1.)** Copy an *unbuilt* version of PRoot into the bootstrap.

**2.)** Chroot into the bootstrap.

**3.)** apt-get update and apt-get install libtalloc-dev and build-essential.

**4.)** Navigate to PRoot/src in the bootstrap and make PRoot.

**5.)** Exit the bootstrap and copy the executable made in the PRoot/src directory
within the bootstrap to your desired location.

### Build a rootfs.obb
**1.)** Chroot into the bootstrap.

**2.)** Make any changes to the file system that you would like to be present
immediately upon running GNURoot Debian. See the createReleaseRootfs.sh script
for examples.

**3.)** Exit the bootstrap.

**4.)** Delete the /dev directory of the bootstrap.

**5.)** Tar up the bootstrap.

### Build GNURootDebian
**1.)** Download a version of Android Studio that supports both the NDK and
experimental gradle plugin 0.7.3.

**2.)** Clone or download the zip file of GNURootDebian.

**3.)** If you built your own version of PRoot, rename the proot executable to
proot.mp2 and copy it to

GNURootDebian/GNURootDebianSource/src/DESIRED\_ARCHITECTURE/assets

**4.)** If you built your own version of rootfs.obb, rename it to

main.ARCH\_INTEGER.com.gnuroot.debian.obb

where ARCH\_INTEGER is 9 for armel, 10 for armhf, and 11 for i386.
Copy the obb to

GNURootDebian/GNURootDebianSource/src/DESIRED\_ARCHITECTURE/obb\_DESIRED\_ARCHITECTURE.

**5.)** Start Android Studio and import GNURootDebian as a project. If prompted
to update your gradle plugin, ignore it.

**6.)** Navigate to Tools -> Android -> SDK Manager -> SDK Platforms and make sure that API levels
15, 21, 22, 23 are all installed. Then navigate to SDK Tools and make sure that the Android
NDK is also installed.

You should now be able to build and run GNURootDebian!

##Extending GNURoot Debian with your own launcher app
GNURoot Debian can be extended fairly easily to create new launchers for other user space programs.
Two examples are currently available as open-source applications:
[GNURoot Octave](https://github.com/corbinlc/GNURootOctave)
[GNURoot Runescape Launcher](https://github.com/corbinlc/GNURootRSInstaller)

Of the two, the Runescape Launcher is probably more simple.

There are a few key ingredients to extending GNURoot Debian:

**1.)** Create a tar.gz file with whatever contents you need. This will typically be at least an
installation script, but could also include files necessary for your program to run.

**2.)** GNURoot Debian accepts intents with the action "com.gnuroot.debian.LAUNCH" which can be
packaged with several extras. Installation will be handled automatically depending on the presence
of certain extras.

**MANDATORY EXTRAS:**
- versionNumber. Check official apps for latest. 
- packageName. The name of YOUR package.

### Installation
**a.)** Include the string extra "launchType" with either "launchTerm" or "launchXTerm". This
this value will determine whether your program is launched in a regular terminal or in a
VNC session once installation is completed.

**b.)** Include the string extra "statusFile" with a name unique to your launcher. _passed or
_failed will be appended to that name as a hidden file in the /support directory. This file
is used to determine if the app can continue.

**c.)** Include the string extra "command" as the command to be run once installation is completed.
This will typically be the location of your program.

**d.)** A data element that is your custom tar file. This must be treated as a URI. In the examples
the tar files are placed in the projects assets directory as .mp2 files, renaming to .tar.gz
once they have been installed, and then converted to a URI so that they can be shared with
GNURoot Debian.

### Launch
**a.)** Include the string extra "launchType" as above.

**b.)** Include the string extra "command" as above.

**c.)** Include the string extra "versionNumber" as described above.

**d.)** Include the string extra "packageName" as described above.

## Credits:

Uses code from bVNC, Terminal Emulator for Android and PRoot.

