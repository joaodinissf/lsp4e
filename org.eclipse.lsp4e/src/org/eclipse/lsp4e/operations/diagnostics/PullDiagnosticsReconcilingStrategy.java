/*******************************************************************************
 * Copyright (c) 2026 🌘🧑‍💻⚗️ (EclipseDevLab) and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria - 🌘🧑‍💻⚗️ (EclipseDevLab) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.operations.diagnostics;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerLifecycle;
import org.eclipse.jface.text.reconciler.DirtyRegion;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServerWrapper;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4e.internal.CancellationUtil;
import org.eclipse.lsp4e.internal.LSPMethods;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRegistrationOptions;
import org.eclipse.lsp4j.DocumentDiagnosticParams;
import org.eclipse.lsp4j.DocumentDiagnosticReport;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageServer;

public class PullDiagnosticsReconcilingStrategy implements IReconcilingStrategy, ITextViewerLifecycle {

	/** The last full report of an identifier, with the result id of the last report if the server sent one. */
	private record Report(@Nullable String resultId, List<Diagnostic> items) {
	}

	/**
	 * What was pulled from a server for a document, for as long as the server is not started again. Guarded
	 * by the monitor of the map it is in.
	 */
	private static final class ServerState {
		final LanguageServer server;
		/**
		 * The last reports by identifier, which stay while the request for an identifier fails or is answered
		 * with nothing having changed.
		 */
		final Map<@Nullable String, Report> reports = new HashMap<>();
		/** The number of pulls so far, of which only the answer to the last one counts. */
		int pulls;
		/** The requests of the last pull, until it is answered. */
		List<CompletableFuture<?>> pending = List.of();

		ServerState(LanguageServer server) {
			this.server = server;
		}
	}

	/**
	 * A document with what was pulled for it from each server. Replaced as a whole on
	 * {@link #setDocument(IDocument)}, so responses to requests for a previous document cannot leak into
	 * what is known of the current one. The map is guarded by its own monitor.
	 */
	private record DocumentState(@Nullable IDocument document, Map<LanguageServerWrapper, ServerState> servers) {
	}

	/** The strategies in use, held weakly as not every owner announces the end of a strategy's life. */
	private static final Set<PullDiagnosticsReconcilingStrategy> INSTANCES = Collections
			.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	private volatile DocumentState state = new DocumentState(null, new HashMap<>());
	private volatile boolean enabled = true;

	public PullDiagnosticsReconcilingStrategy() {
		INSTANCES.add(this);
	}

	/**
	 * Requests the diagnostics of the given server again for every document that diagnostics are pulled
	 * for, as a server asks for with {@code workspace/diagnostic/refresh}.
	 */
	public static void refresh(LanguageServerWrapper wrapper) {
		final List<PullDiagnosticsReconcilingStrategy> strategies;
		synchronized (INSTANCES) {
			strategies = List.copyOf(INSTANCES);
		}
		strategies.forEach(strategy -> strategy.updateDiagnostics(wrapper));
	}

	@Override
	public void install(@Nullable ITextViewer textViewer) {
		enabled = true;
		updateDiagnostics();
	}

	@Override
	public void uninstall() {
		enabled = false;
	}

	@Override
	public void setDocument(@Nullable IDocument document) {
		this.state = new DocumentState(document, new HashMap<>());
		// delay update on document set as server could be initializing and the registration options for textdocument/diagnostic
		// capability not yet sent. Delaying can then prevent from synchronization issues without being perceived as annoyance
		// by users at this stage as the editor is initializing in other ways as well.
		CompletableFuture.runAsync(this::updateDiagnostics, CompletableFuture.delayedExecutor(2000, TimeUnit.MILLISECONDS));
	}

	private void updateDiagnostics() {
		updateDiagnostics(null);
	}

	/**
	 * @param onlyServer the only server to request diagnostics from, or {@code null} for all servers
	 */
	private void updateDiagnostics(@Nullable LanguageServerWrapper onlyServer) {
		final DocumentState currentState = this.state;
		final var doc = currentState.document();
		if (doc == null || !enabled) {
			return;
		}
		var id = LSPEclipseUtils.toTextDocumentIdentifier(doc);
		if (id == null) {
			return;
		}
		final URI uri = LSPEclipseUtils.toUri(doc);
		if (uri == null) {
			return;
		}
		removeDiagnosticsOfUnregistered(currentState, uri);
		if (onlyServer != null) {
			// not by way of all servers of the document, which starts those that do not run, and only if
			// this is a document of the server at all
			if (onlyServer.isActive() && onlyServer.isConnectedTo(uri) && !getIdentifiers(onlyServer, uri).isEmpty()) {
				onlyServer.execute(ls -> pullDiagnostics(currentState, onlyServer, ls, id, uri));
			}
			return;
		}
		// LanguageServers.forDocument evaluates the filter against the document's effective capabilities,
		// so servers whose diagnostic registrations' documentSelectors don't match are skipped
		LanguageServers.forDocument(doc) //
				.withFilter(capabilities -> capabilities.getDiagnosticProvider() != null) //
				.collectAll((wrapper, ls) -> pullDiagnostics(currentState, wrapper, ls, id, uri));
	}

	/**
	 * Removes the diagnostics of the servers that do not register any identifier for the document anymore,
	 * as those are not pulled from anymore.
	 */
	private static void removeDiagnosticsOfUnregistered(DocumentState state, URI uri) {
		final List<LanguageServerWrapper> wrappers;
		synchronized (state.servers()) {
			wrappers = List.copyOf(state.servers().keySet());
		}
		for (final LanguageServerWrapper wrapper : wrappers) {
			// not those of a server that was stopped, which registers nothing either
			if (!wrapper.isActive() || !getIdentifiers(wrapper, uri).isEmpty()) {
				continue;
			}
			final ServerState server;
			synchronized (state.servers()) {
				server = state.servers().remove(wrapper);
			}
			final var client = wrapper.getLanguageClient();
			if (server != null && client != null) {
				server.pending.forEach(request -> request.cancel(true));
				client.publishDiagnostics(new PublishDiagnosticsParams(uri.toString(), List.of()));
			}
		}
	}

	/**
	 * Requests the diagnostics of every identifier the server registered for the document and publishes
	 * them together, as each publication replaces the server's previous diagnostics for the document.
	 * An identifier whose request fails contributes its last full report, so that neither the other
	 * identifiers' new diagnostics nor its own previous ones are lost.
	 * <p>
	 * The requests of the pull before are cancelled if they are not answered yet, and their answers are
	 * ignored. So the answer that nothing changed always is about the last report.
	 */
	private static CompletableFuture<@Nullable Void> pullDiagnostics(DocumentState state,
			LanguageServerWrapper wrapper, LanguageServer ls, TextDocumentIdentifier id, URI uri) {
		final var previousResultIds = new HashMap<@Nullable String, String>();
		final ServerState server;
		final int pull;
		final List<CompletableFuture<?>> overtaken;
		synchronized (state.servers()) {
			// a server that was started again knows nothing about the result ids of the one before
			server = state.servers().compute(wrapper,
					(w, last) -> last != null && last.server == ls ? last : new ServerState(ls));
			pull = ++server.pulls;
			overtaken = server.pending;
			server.pending = List.of();
			server.reports.forEach((identifier, report) -> {
				final String resultId = report.resultId();
				if (resultId != null) {
					previousResultIds.put(identifier, resultId);
				}
			});
		}
		// outside of the monitor, as it tells the server
		overtaken.forEach(request -> request.cancel(true));
		final var requests = new HashMap<@Nullable String, CompletableFuture<Optional<DocumentDiagnosticReport>>>();
		final var sent = new ArrayList<CompletableFuture<?>>();
		for (final String identifier : getIdentifiers(wrapper, uri)) {
			final var param = new DocumentDiagnosticParams(id);
			param.setIdentifier(identifier);
			// lets the server answer that nothing changed instead of sending the same diagnostics again
			final String previousResultId = previousResultIds.get(identifier);
			if (previousResultId != null) {
				param.setPreviousResultId(previousResultId);
			}
			final var request = ls.getTextDocumentService().diagnostic(param);
			sent.add(request);
			requests.put(identifier, request.thenApply(Optional::ofNullable).exceptionally(ex -> {
				if (!CancellationUtil.isRequestCancelledException(ex)) {
					LanguageServerPlugin.logError(ex);
				}
				return Optional.empty();
			}));
		}
		final boolean overtakenAlready;
		synchronized (state.servers()) {
			overtakenAlready = server.pulls != pull;
			if (!overtakenAlready) {
				server.pending = sent;
			}
		}
		if (overtakenAlready) {
			sent.forEach(request -> request.cancel(true));
		}
		return CompletableFuture.allOf(requests.values().toArray(CompletableFuture[]::new)).thenRun(() -> {
			synchronized (state.servers()) {
				if (state.servers().get(wrapper) != server || server.pulls != pull) {
					// the server was started or asked again since
					return;
				}
				server.pending = List.of();
				final Map<@Nullable String, Report> last = server.reports;
				// the diagnostics of an identifier that the server does not register anymore are gone
				boolean changed = last.keySet().retainAll(requests.keySet());
				for (final var request : requests.entrySet()) {
					final @Nullable String identifier = request.getKey();
					final DocumentDiagnosticReport report = request.getValue().join().orElse(null);
					if (report == null) {
						continue;
					}
					if (report.isLeft()) {
						changed = true;
						final List<Diagnostic> items = report.getLeft().getItems();
						last.put(identifier, new Report(report.getLeft().getResultId(), items == null ? List.of() : items));
					} else {
						final Report previous = last.get(identifier);
						if (previous != null) {
							// nothing changed, only the result id to send next time may have
							last.put(identifier, new Report(report.getRight().getResultId(), previous.items()));
						}
					}
				}
				final var client = wrapper.getLanguageClient();
				if (changed && client != null) {
					final var diagnostics = last.values().stream().flatMap(report -> report.items().stream()).toList();
					client.publishDiagnostics(new PublishDiagnosticsParams(uri.toString(), diagnostics));
				}
			}
		});
	}

	/**
	 * @return the identifiers of the static and dynamic {@code textDocument/diagnostic} registrations
	 *         whose {@code documentSelector} matches the document
	 */
	private static Set<@Nullable String> getIdentifiers(LanguageServerWrapper wrapper, URI uri) {
		return wrapper
				.getRegistrationOptions(uri, LSPMethods.TEXT_DOCUMENT_DIAGNOSTIC, DiagnosticRegistrationOptions.class)
				.stream().map(DiagnosticRegistrationOptions::getIdentifier).collect(Collectors.toSet());
	}

	@Override
	public void reconcile(@Nullable DirtyRegion dirtyRegion, @Nullable IRegion subRegion) {
		updateDiagnostics();
	}

	@Override
	public void reconcile(@Nullable IRegion partition) {
		updateDiagnostics();
	}

}
