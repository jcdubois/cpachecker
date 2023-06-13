// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

/* Generated by CIL v. 1.3.7 */
/* print_CIL_Input is true */

#line 211 "/usr/lib/gcc/i486-linux-gnu/4.4.3/include/stddef.h"
typedef unsigned int size_t;
#line 8 "test3.c"
struct node {
   int val ;
   struct node *next ;
};
#line 471 "/usr/include/stdlib.h"
extern  __attribute__((__nothrow__)) void *malloc(size_t __size )  __attribute__((__malloc__)) ;
#line 13 "test3.c"
int main(void) 
{ struct node *fst ;
  struct node *tmp ;
  void *tmp___0 ;
  void *tmp___1 ;
  unsigned int __cil_tmp6 ;
  void *__cil_tmp7 ;
  unsigned int __cil_tmp8 ;
  unsigned int __cil_tmp9 ;
  unsigned int __cil_tmp10 ;
  unsigned int __cil_tmp11 ;
  void *__cil_tmp12 ;
  unsigned int __cil_tmp13 ;
  unsigned int __cil_tmp14 ;
  unsigned int __cil_tmp15 ;
  struct node *__cil_tmp16 ;
  unsigned int __cil_tmp17 ;
  unsigned int __cil_tmp18 ;
  struct node *__cil_tmp19 ;
  unsigned int __cil_tmp20 ;
  unsigned int __cil_tmp21 ;
  struct node *__cil_tmp22 ;
  struct node **mem_23 ;
  struct node **mem_24 ;
  int *mem_25 ;
  struct node **mem_26 ;
  struct node **mem_27 ;
  int *mem_28 ;

  {
  {
#line 18
  tmp___0 = malloc(8U);
#line 18
  fst = (struct node *)tmp___0;
  }
  {
#line 20
  __cil_tmp6 = (unsigned int )fst;
#line 20
  __cil_tmp7 = (void *)0;
#line 20
  __cil_tmp8 = (unsigned int )__cil_tmp7;
#line 20
  if (__cil_tmp8 == __cil_tmp6) {
#line 21
    return (1);
  } else {

  }
  }
  {
#line 24
  tmp___1 = malloc(8U);
#line 24
  tmp = (struct node *)tmp___1;
#line 25
  __cil_tmp9 = (unsigned int )fst;
#line 25
  __cil_tmp10 = __cil_tmp9 + 4;
#line 25
  mem_23 = (struct node **)__cil_tmp10;
#line 25
  *mem_23 = tmp;
  }
  {
#line 27
  __cil_tmp11 = (unsigned int )tmp;
#line 27
  __cil_tmp12 = (void *)0;
#line 27
  __cil_tmp13 = (unsigned int )__cil_tmp12;
#line 27
  if (__cil_tmp13 == __cil_tmp11) {
#line 28
    return (1);
  } else {

  }
  }
#line 31
  __cil_tmp14 = (unsigned int )fst;
#line 31
  __cil_tmp15 = __cil_tmp14 + 4;
#line 31
  mem_24 = (struct node **)__cil_tmp15;
#line 31
  __cil_tmp16 = *mem_24;
#line 31
  mem_25 = (int *)__cil_tmp16;
#line 31
  *mem_25 = 1;
#line 32
  __cil_tmp17 = (unsigned int )fst;
#line 32
  __cil_tmp18 = __cil_tmp17 + 4;
#line 32
  mem_26 = (struct node **)__cil_tmp18;
#line 32
  __cil_tmp19 = *mem_26;
#line 32
  __cil_tmp20 = (unsigned int )__cil_tmp19;
#line 32
  __cil_tmp21 = __cil_tmp20 + 4;
#line 32
  mem_27 = (struct node **)__cil_tmp21;
#line 32
  __cil_tmp22 = *mem_27;
#line 32
  mem_28 = (int *)__cil_tmp22;
#line 32
  *mem_28 = 1;
#line 34
  return (0);
}
}
