// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

void reach_error() {}
int main() {
  int x = sizeof(int);
  unsigned long long z = 2147483647 + sizeof(int);
  if (z!= 2147483647ULL+sizeof(int)) {
    reach_error();
  }
}

