// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang.psi

interface MermaidGitGraphBranchIdentifierHolder: MermaidPsiElement {
  val gitGraphBranchIdentifier: MermaidGitGraphBranchIdentifier
}

fun MermaidGitGraphBranchIdentifierHolder.isQuoted(): Boolean {
  return gitGraphBranchIdentifier.quotedBranchIdentifier != null
}

// TODO: Probably should return [MermaidIdentifier]
// TODO: [MermaidQuotedBranchIdentifier] should probably implement [MermaidIdentifier]
fun MermaidGitGraphBranchIdentifierHolder.branchIdentifier(): MermaidPsiElement {
  return gitGraphBranchIdentifier.identifier ?: gitGraphBranchIdentifier.quotedBranchIdentifier!!
}
