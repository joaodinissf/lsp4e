/*******************************************************************************
 * Copyright (c) 2022 Avaloq Group AG.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4e.test.semanticTokens;

import static org.eclipse.lsp4e.test.utils.TestUtils.waitForAndAssertCondition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.lsp4e.test.utils.AbstractTestWithProject;
import org.eclipse.lsp4e.test.utils.TestUtils;
import org.eclipse.lsp4e.tests.mock.MockLanguageServerFactory;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.junit.jupiter.api.Test;

public class SemanticHighlightReconcilerStrategyTest extends AbstractTestWithProject {

	@Test
	public void testKeyword(MockLanguageServerFactory factory) throws CoreException {
		List<String> tokenTypes = List.of("keyword");
		List<String> tokenModifiers = List.of("obsolete");
		SemanticTokensTestUtil.setSemanticTokensLegend(tokenTypes, tokenModifiers, factory);
		
		final var semanticTokens = new SemanticTokens();
		semanticTokens.setData(SemanticTokensTestUtil.keywordSemanticTokens());

		factory.withConfiguration((idx, server) -> {
			server.getTextDocumentService().setSemanticTokens(semanticTokens);
		});

		IFile file = TestUtils.createUniqueTestFile(project, "lsptm", SemanticTokensTestUtil.keywordText);
		ITextViewer textViewer = TestUtils.openTextViewer(file);

		StyledText textWidget = textViewer.getTextWidget();
		// The ranges between the keywords depend on the TextMate theme, which follows the
		// platform's light/dark appearance, so only the semantic keyword ranges are checked
		waitForAndAssertCondition(5_000, () -> {
			assertKeywordRange(textWidget, 0, 4); // type
			assertKeywordRange(textWidget, 15, 4); // type
			assertKeywordRange(textWidget, 24, 7); // extends
			return true;
		});
	}

	private static void assertKeywordRange(StyledText textWidget, int start, int length) {
		StyleRange styleRange = Arrays.stream(textWidget.getStyleRanges()) //
				.filter(range -> range.start == start).findFirst().orElse(null);
		assertNotNull(styleRange, "No style range starting at offset " + start);
		assertEquals(length, styleRange.length);
		assertNotNull(styleRange.foreground);
		assertNotEquals(textWidget.getBackground(), styleRange.foreground);
	}
}
