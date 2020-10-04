// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.model.java;

import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.java.JStatement;
import org.sosy_lab.cpachecker.cfa.model.CFANode;

public class JThrowStatementEdge extends JStatementEdge {

  public JThrowStatementEdge(
      String pRawStatement,
      JStatement pStatement,
      FileLocation pFileLocation,
      CFANode pPredecessor, CFANode pSuccessor) {
    super(pRawStatement, pStatement, pFileLocation, pPredecessor, pSuccessor);
  }
}
