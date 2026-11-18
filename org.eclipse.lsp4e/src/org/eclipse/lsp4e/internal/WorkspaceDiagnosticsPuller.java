/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4e.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServerWrapper;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRegistrationOptions;
import org.eclipse.lsp4j.PreviousResultId;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.WorkspaceDiagnosticParams;
import org.eclipse.lsp4j.WorkspaceDiagnosticReport;
import org.eclipse.lsp4j.WorkspaceDiagnosticReportPartialResult;
import org.eclipse.lsp4j.WorkspaceDocumentDiagnosticReport;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * Pulls the workspace diagnostics of a language server, for as long as it runs and for each identifier
 * it registered {@code textDocument/diagnostic} with {@code workspaceDiagnostics}. As the specification
 * recommends, {@code workspace/diagnostic} is requested again once answered, after a short delay.
 * <p>
 * Reports for documents connected to the server are dropped, as those get their diagnostics from
 * {@code textDocument/diagnostic}. Nothing is remembered about them, so that the server reports them in
 * full again once they are closed. So are reports for documents that are not in the workspace, as there is
 * nowhere to show them.
 * <p>
 * A server may answer a request little by little with partial results, and only complete it when it wants
 * to be asked again. Those are handled like complete answers.
 * <p>
 * The diagnostics of a document are the union of what each identifier last reported for it.
 */
public final class WorkspaceDiagnosticsPuller {

	private static final long DELAY_BETWEEN_PULLS_MS = 2000;
	private static final String PARTIAL_RESULT_TOKEN_PREFIX = "lsp4e-workspace-diagnostics-"; //$NON-NLS-1$
	/** The number of consecutive failed requests after which an identifier is not pulled anymore. */
	private static final int MAX_CONSECUTIVE_ERRORS = 5;

	private final LanguageServerWrapper wrapper;
	/** The running pulls by identifier, which may be {@code null}. Guarded by {@code this}. */
	private final Map<@Nullable String, Pull> pulls = new HashMap<>();
	private @Nullable LanguageServer server;
	private @Nullable LanguageClient client;

	public WorkspaceDiagnosticsPuller(LanguageServerWrapper wrapper) {
		this.wrapper = wrapper;
	}

	/**
	 * Starts pulling from the given server, which must be initialized.
	 *
	 * @param client where to publish the diagnostics
	 */
	public synchronized void start(LanguageServer server, LanguageClient client) {
		stop();
		this.server = server;
		this.client = client;
		update();
	}

	/** Starts and stops pulls according to the current registrations of the server. */
	public synchronized void update() {
		final LanguageServer server = this.server;
		final LanguageClient client = this.client;
		if (server == null || client == null) {
			return;
		}
		final Set<@Nullable String> identifiers = wrapper
				.getRegistrationOptions(null, LSPMethods.TEXT_DOCUMENT_DIAGNOSTIC, DiagnosticRegistrationOptions.class)
				.stream() //
				.filter(DiagnosticRegistrationOptions::isWorkspaceDiagnostics) //
				.map(DiagnosticRegistrationOptions::getIdentifier) //
				.collect(Collectors.toSet());
		final var removed = new ArrayList<Pull>();
		pulls.entrySet().removeIf(pull -> {
			if (identifiers.contains(pull.getKey())) {
				return false;
			}
			pull.getValue().stop();
			removed.add(pull.getValue());
			return true;
		});
		// what the identifiers that are gone reported is not valid anymore
		removed.forEach(pull -> pull.diagnostics.keySet().forEach(uri -> publish(client, uri)));
		for (final @Nullable String identifier : identifiers) {
			if (!pulls.containsKey(identifier)) {
				final var pull = new Pull(server, client, identifier);
				pulls.put(identifier, pull);
				pull.request();
			}
		}
	}

	/** Pulls again the identifiers that were given up on after repeated errors. */
	public synchronized void refresh() {
		pulls.values().forEach(Pull::restart);
	}

	/**
	 * Handles the given progress if it is a partial result of one of the requests sent from here.
	 *
	 * @return whether the progress was handled
	 */
	public boolean notifyProgress(ProgressParams params) {
		final var token = params.getToken();
		final var value = params.getValue();
		if (!token.isLeft() || !token.getLeft().startsWith(PARTIAL_RESULT_TOKEN_PREFIX) || !value.isRight()) {
			return false;
		}
		final Pull pull;
		synchronized (this) {
			pull = pulls.values().stream().filter(p -> token.getLeft().equals(p.partialResultToken)).findAny()
					.orElse(null);
		}
		if (pull != null) {
			pull.partialResult(value.getRight());
		}
		// a partial result that comes too late is of no interest to anyone else either
		return true;
	}

	/** Forgets what was reported for the given document, so that the server reports it in full again. */
	public synchronized void forget(URI uri) {
		final String key = uri.toString();
		for (final Pull pull : pulls.values()) {
			pull.resultIds.remove(key);
			pull.diagnostics.remove(key);
		}
	}

	private synchronized void publish(LanguageClient client, String uri) {
		if (wrapper.isConnectedTo(LSPEclipseUtils.toUri(uri))) {
			return;
		}
		final var diagnostics = new ArrayList<Diagnostic>();
		for (final Pull pull : pulls.values()) {
			diagnostics.addAll(pull.diagnostics.getOrDefault(uri, List.of()));
		}
		client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
	}

	public synchronized void stop() {
		pulls.values().forEach(Pull::stop);
		pulls.clear();
		this.server = null;
		this.client = null;
	}

	private final class Pull {
		private final LanguageServer server;
		private final LanguageClient client;
		private final @Nullable String identifier;
		/** The result id of the last report by document URI, as sent by the server. */
		private final Map<String, String> resultIds = new ConcurrentHashMap<>();
		/** The diagnostics last published by document URI, as sent by the server. */
		private final Map<String, List<Diagnostic>> diagnostics = new ConcurrentHashMap<>();
		private volatile boolean stopped;
		private volatile boolean gaveUp;
		private volatile @Nullable CompletableFuture<WorkspaceDiagnosticReport> pending;
		private volatile int consecutiveErrors;
		/** The token of the last request, with which the server may send partial results. */
		private volatile @Nullable String partialResultToken;

		Pull(LanguageServer server, LanguageClient client, @Nullable String identifier) {
			this.server = server;
			this.client = client;
			this.identifier = identifier;
		}

		void request() {
			if (stopped) {
				return;
			}
			final var params = new WorkspaceDiagnosticParams(resultIds.entrySet().stream()
					// what is known of a document that was opened since is not updated anymore
					.filter(resultId -> !wrapper.isConnectedTo(LSPEclipseUtils.toUri(resultId.getKey())))
					.map(resultId -> new PreviousResultId(resultId.getKey(), resultId.getValue())).toList());
			params.setIdentifier(identifier);
			final String token = PARTIAL_RESULT_TOKEN_PREFIX + UUID.randomUUID();
			partialResultToken = token;
			params.setPartialResultToken(token);
			final CompletableFuture<WorkspaceDiagnosticReport> request;
			try {
				request = server.getWorkspaceService().diagnostic(params);
			} catch (RuntimeException ex) {
				LanguageServerPlugin.logError(ex);
				return;
			}
			pending = request;
			request.whenComplete(this::completed);
			if (stopped) {
				request.cancel(true);
			}
		}

		private void completed(@Nullable WorkspaceDiagnosticReport report, @Nullable Throwable ex) {
			if (stopped) {
				return;
			}
			if (ex == null) {
				consecutiveErrors = 0;
				if (report != null) {
					try {
						report.getItems().forEach(this::accept);
					} catch (RuntimeException e) {
						LanguageServerPlugin.logError(e);
					}
				}
			} else if (!CancellationUtil.isRequestCancelledException(ex)) {
				LanguageServerPlugin.logError(ex);
				if (++consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
					gaveUp = true;
					return;
				}
			}
			CompletableFuture.delayedExecutor(DELAY_BETWEEN_PULLS_MS, TimeUnit.MILLISECONDS).execute(this::request);
		}

		void partialResult(Object value) {
			if (stopped) {
				return;
			}
			try {
				final var gson = JsonUtil.LSP4J_GSON;
				final var result = value instanceof WorkspaceDiagnosticReportPartialResult partialResult ? partialResult
						: gson.fromJson(gson.toJsonTree(value), WorkspaceDiagnosticReportPartialResult.class);
				if (result != null && result.getItems() != null) {
					result.getItems().forEach(this::accept);
				}
			} catch (RuntimeException e) {
				LanguageServerPlugin.logError(e);
			}
		}

		void restart() {
			if (gaveUp) {
				gaveUp = false;
				consecutiveErrors = 0;
				request();
			}
		}

		private void accept(WorkspaceDocumentDiagnosticReport report) {
			final boolean isFull = report.isWorkspaceFullDocumentDiagnosticReport();
			final String uri = isFull ? report.getWorkspaceFullDocumentDiagnosticReport().getUri()
					: report.getWorkspaceUnchangedDocumentDiagnosticReport().getUri();
			final String resultId = isFull ? report.getWorkspaceFullDocumentDiagnosticReport().getResultId()
					: report.getWorkspaceUnchangedDocumentDiagnosticReport().getResultId();
			if (wrapper.isConnectedTo(LSPEclipseUtils.toUri(uri))) {
				return;
			}
			if (resultId != null) {
				resultIds.put(uri, resultId);
			} else {
				resultIds.remove(uri);
			}
			if (!isFull) {
				return;
			}
			final IResource resource = LSPEclipseUtils.findResourceFor(uri);
			if (resource == null || !resource.isAccessible()) {
				return;
			}
			final List<Diagnostic> items = report.getWorkspaceFullDocumentDiagnosticReport().getItems();
			if (!items.equals(diagnostics.put(uri, items))) {
				publish(client, uri);
			}
		}

		void stop() {
			stopped = true;
			final var pending = this.pending;
			if (pending != null) {
				pending.cancel(true);
			}
		}
	}
}
