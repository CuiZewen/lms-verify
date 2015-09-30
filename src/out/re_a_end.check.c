#include <string.h>
/*@
requires ((strlen(x0)>=0) && \valid(x0+(0..strlen(x0))));
*/
int matcher(char  * x0) {
  int x2 = -1;
  int x3 = 0/*false*/;
  int x7 = strlen(x0);
  /*@
  loop invariant ((-1<=x2) && (x2<=strlen(x0)));
  loop assigns x2, x3;
  loop variant (strlen(x0)-x2);
  */
  for (;;) {
    int x4 = x3;
    int x5 = !x4;
    int x10;
    if (x5) {
      int x6 = x2;
      int x8 = x6 < x7;
      x10 = x8;
    } else {
      x10 = 0/*false*/;
    }
    if (!x10) break;
    x2 += 1;
    int x13 = x2;
    int x14 = x13 < x7;
    int x18;
    if (x14) {
      int x15 = x0[x13];
      int x16 = 'a' == x15;
      int x17 = 0/*false*/ || x16;
      x18 = x17;
    } else {
      x18 = 0/*false*/;
    }
    int x21;
    if (x18) {
      int x19 = x13 + 1;
      int x20 = x19 == x7;
      x21 = x20;
    } else {
      x21 = 0/*false*/;
    }
    x3 = x21;
  }
  int x37 = x3;
  return x37;
}