/*******************************************************************************
 * Copyright (c) 2025 Vegard IT GmbH and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Sebastian Thomschke (Vegard IT GmbH) - initial implementation.
 *******************************************************************************/
package org.eclipse.lsp4e.internal;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.operations.inlinecompletion.GhostTextRegistry;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 * Base class for LSP-backed code mining providers that:
 * <ul>
 * <li>compute code minings asynchronously per document using LSP requests</li>
 * <li>track at most one in-flight request per document and cancel the previous
 * one when a new computation starts</li>
 * </ul>
 */
public abstract class AbstractLSPCodeMiningProvider extends AbstractCodeMiningProvider {

	private final ConcurrentMap<IDocument, CompletableFuture<List<? extends ICodeMining>>> pendingRequests = new ConcurrentHashMap<>();

	/** Code minings computed for a document version, reused while neither it nor the server changes. */
	private record CachedMinings(long modificationStamp, long refreshGeneration,
			CompletableFuture<List<? extends ICodeMining>> minings) {
	}

	private static final AtomicLong REFRESH_GENERATION = new AtomicLong();

	private final ConcurrentMap<IDocument, CachedMinings> cache = new ConcurrentHashMap<>();

	/**
	 * Makes all providers compute their code minings anew, e.g. because a server asked for a refresh.
	 * Otherwise, minings computed for a document are reused until it is modified: code minings are
	 * refreshed for reasons unrelated to them, such as inline completion ghost text being shown.
	 */
	public static void invalidateCaches() {
		REFRESH_GENERATION.incrementAndGet();
	}

	/**
	 * Computes code minings for the given document.
	 *
	 * @return a future producing the list of code minings, or {@code null} if no
	 *         code minings are available
	 */
	protected abstract @Nullable CompletableFuture<List<? extends ICodeMining>> doProvideCodeMinings(IDocument doc,
			TextDocumentIdentifier docId);

	@Override
	public final @Nullable CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(final ITextViewer viewer,
			final IProgressMonitor monitor) {
		final IDocument document = viewer.getDocument();
		if (document == null) {
			return null;
		}

		final URI docURI = LSPEclipseUtils.toUri(document);
		if (docURI == null)
			return null;

		final TextDocumentIdentifier docId = LSPEclipseUtils.toTextDocumentIdentifier(docURI);

		final long modificationStamp = document instanceof IDocumentExtension4 extension
				? extension.getModificationStamp()
				: IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
		final long refreshGeneration = REFRESH_GENERATION.get();
		final CachedMinings cached = cache.get(document);
		if (cached != null && modificationStamp != IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP
				&& cached.modificationStamp() == modificationStamp && cached.refreshGeneration() == refreshGeneration
				&& cached.minings().isDone() && !cached.minings().isCompletedExceptionally()) {
			return cached.minings().thenApply(minings -> GhostTextRegistry.filter(document, minings));
		}

		final var computed = doProvideCodeMinings(document, docId);
		if (computed == null) {
			cache.remove(document);
		} else {
			cache.put(document, new CachedMinings(modificationStamp, refreshGeneration, computed));
		}
		// leave room for inline completion ghost text; cancelling the result cancels the computation
		final CompletableFuture<List<? extends ICodeMining>> current = computed == null ? null
				: computed.thenApply(minings -> GhostTextRegistry.filter(document, minings));
		if (computed != null && current != null) {
			current.whenComplete((minings, error) -> {
				if (current.isCancelled()) {
					computed.cancel(true);
				}
			});
		}
		final CompletableFuture<List<? extends ICodeMining>> previous;
		if (current == null) {
			previous = pendingRequests.remove(document);
		} else {
			previous = pendingRequests.put(document, current);
		}
		if (previous != null && !previous.isDone()) {
			previous.cancel(true);
		}

		return current;
	}

	@Override
	public void dispose() {
		cache.clear();
		super.dispose();
	}
}
