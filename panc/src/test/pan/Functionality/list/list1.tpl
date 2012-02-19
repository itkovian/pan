#
# test of list creation by index
#
# @expect="/nlist[@name='profile']/list[@name='l1']/*[1]='first' and /nlist[@name='profile']/list[@name='l2']/*[1]='first' and /nlist[@name='profile']/list[@name='l3']/*[1]='first' and /nlist[@name='profile']/list[@name='l1']/*[2]='second' and /nlist[@name='profile']/list[@name='l2']/*[2]='second' and /nlist[@name='profile']/list[@name='l3']/*[2]='second' and /nlist[@name='profile']/list[@name='l1']/*[3]='third' and /nlist[@name='profile']/list[@name='l2']/*[3]='third' and /nlist[@name='profile']/list[@name='l3']/*[3]='third'"
# @format=pan
#

object template list1;

"/l1/0" = "first";
"/l1/1" = "second";
"/l1/2" = "third";

"/l2/2" = "third";
"/l2/1" = "second";
"/l2/0" = "first";

"/l3/2" = "third";
"/l3/0" = "first";
"/l3/1" = "second";
