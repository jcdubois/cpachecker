// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.postprocessing.function;

import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFAReversePostorder;
import org.sosy_lab.cpachecker.cfa.CfaPostProcessor.ReadOnlyIndependentFunctionPostProcessor;
import org.sosy_lab.cpachecker.cfa.MutableCFA;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;

public final class ReversePostorderPostProcessor
    implements ReadOnlyIndependentFunctionPostProcessor {

  @Override
  public void process(MutableCFA pCfa, LogManager pLogger) {

    for (FunctionEntryNode function : pCfa.getAllFunctionHeads()) {
      CFAReversePostorder sorter = new CFAReversePostorder();
      sorter.assignSorting(function);
    }
  }
}
