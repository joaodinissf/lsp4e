/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4e.operations.inlinecompletion;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.ISourceViewerExtension5;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Shows inline completions ({@code textDocument/inlineCompletion}) as ghost text.
 * <p>
 * Code mining providers are created per editor, so each instance owns the
 * {@link InlineCompletionController} of its editor's viewer, which it installs the first time it is
 * asked for minings. The minings it returns are computed by the controller beforehand, so it never
 * delays the other providers.
 */
public class InlineCompletionProvider extends AbstractCodeMiningProvider {

	private @Nullable InlineCompletionController controller;

	@Override
	public @Nullable CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(ITextViewer viewer,
			IProgressMonitor monitor) {
		if (!(viewer instanceof ISourceViewer sourceViewer && viewer instanceof ISourceViewerExtension5)) {
			return CompletableFuture.completedFuture(List.of());
		}
		InlineCompletionController current = controller;
		if (current == null) {
			if (Display.getCurrent() == null) {
				viewer.getTextWidget().getDisplay().asyncExec(() -> getController(sourceViewer));
				return CompletableFuture.completedFuture(List.of());
			}
			current = getController(sourceViewer);
		}
		return CompletableFuture.completedFuture(current.createMinings(this));
	}

	private InlineCompletionController getController(ISourceViewer viewer) {
		InlineCompletionController current = controller;
		if (current == null) {
			current = new InlineCompletionController(viewer, getAdapter(ITextEditor.class));
			current.install();
			controller = current;
		}
		return current;
	}

	/** Returns the controller of this provider's editor, or {@code null} before it is installed. */
	public @Nullable InlineCompletionController getController() {
		return controller;
	}

	@Override
	public void dispose() {
		final InlineCompletionController current = controller;
		controller = null;
		if (current != null) {
			current.uninstall();
		}
		super.dispose();
	}
}
