#!/bin/sh -e
# get necessary packages
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get -y install binfmt-support qemu qemu-user-static debootstrap
rm -rf $2
mkdir $2
cp -r PRoot $2 
cp -r disableselinux.c $2 
if [ "$1" = "i386" ] ; then
  debootstrap --variant=minbase --include=sudo,dropbear jessie $2 http://ftp.debian.org/debian
else
  qemu-debootstrap --arch=$1 --variant=minbase --include=sudo,dropbear jessie $2 http://ftp.debian.org/debian
fi
#reduce size
DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true \
 LC_ALL=C LANGUAGE=C LANG=C chroot $2 apt-get clean

#basic dns setup
echo "127.0.0.1 localhost" > $2/etc/hosts
echo "nameserver 8.8.8.8" > $2/etc/resolv.conf
echo "nameserver 8.8.4.4" >> $2/etc/resolv.conf

#basic sources.list setup
echo "deb http://http.debian.net/debian/ jessie contrib main non-free" >> $2/etc/apt/sources.list
echo "#deb-src http://http.debian.net/debian/ jessie main contrib" >> $2/etc/apt/sources.list
echo "" >> $2/etc/apt/sources.list
echo "deb http://security.debian.org/ jessie/updates contrib main non-free" >> $2/etc/apt/sources.list
echo "#deb-src http://security.debian.org/ jessie/updates main contrib" >> $2/etc/apt/sources.list
echo "" >> $2/etc/apt/sources.list
echo "deb http://http.debian.net/debian/ jessie-updates contrib main non-free" >> $2/etc/apt/sources.list
echo "#deb-src http://http.debian.net/debian/ jessie-updates main contrib" >> $2/etc/apt/sources.list
echo "" >> $2/etc/apt/sources.list
echo "deb http://http.debian.net/debian/ jessie-backports contrib main non-free" >> $2/etc/apt/sources.list
echo "#deb-src http://http.debian.net/debian/ jessie-backports main contrib" >> $2/etc/apt/sources.list

#add a non-root user
echo "#!/bin/bash" > $2/addNonRoot.sh
echo "useradd non-root -s /bin/bash -m -u 2048" >> $2/addNonRoot.sh
echo "echo non-root:gnuroot | chpasswd" >> $2/addNonRoot.sh
chmod 777 $2/addNonRoot.sh
DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true \
 LC_ALL=C LANGUAGE=C LANG=C chroot $2 ./addNonRoot.sh
rm $2/addNonRoot.sh

#tar this up into .obb file
cd $2
rm -rf ../$1.obb
rm -rf dev/*
tar -czvf ../$1.obb ./*

#build proot to go with the release
DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true \
 LC_ALL=C LANGUAGE=C LANG=C chroot . apt-get update
DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true \
 LC_ALL=C LANGUAGE=C LANG=C chroot . apt-get -y install build-essential libtalloc2 libtalloc-dev
DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true \
 LC_ALL=C LANGUAGE=C LANG=C chroot . make -C PRoot/src clean
DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true \
 LC_ALL=C LANGUAGE=C LANG=C chroot . make -C PRoot/src 
rm -rf ../$1_proot
cp PRoot/src/proot ../$1_proot

#build disableselinux to go with this release
DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true \
 LC_ALL=C LANGUAGE=C LANG=C chroot . gcc -shared -fpic disableselinux.c -o libdisableselinux.so 
cp libdisableselinux.so ../$1_libdisableselinux.so

#get busybox to go with the release
DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true \
 LC_ALL=C LANGUAGE=C LANG=C chroot . apt-get -y install busybox-static 
rm -rf ../$1_busybox
cp bin/busybox ../$1_busybox


