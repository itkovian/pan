#
# @expect="/profile/a=1 and /profile/b=2"
# @format=xmldb
#
object template default22;

type g = {
  'a' : string = '1'
  'b' : string = '2'
};

bind '/' = g;
