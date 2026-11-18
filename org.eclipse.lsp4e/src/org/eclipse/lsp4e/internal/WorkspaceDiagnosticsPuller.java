/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4e.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServerWrapper;
import org.eclipse.lsp4j.DiagnosticRegistrationOptions;
import org.eclipse.lsp4j.PreviousResultId;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.WorkspaceDiagnosticParams;
import org.eclipse.lsp4j.WorkspaceDiagnosticReport;
import org.eclipse.lsp4j.WorkspaceDocumentDiagnosticReport;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * Pulls the workspace diagnostics of a language server, for as long as it runs and for each identifier
 * it registered {@code textDocument/diagnostic} with {@code workspaceDiagnostics}. As the specification
 * recommends, {@code workspace/diagnostic} is requested again once answered, after a short delay.
 * <p>
 * Reports for documents connected to the server are dropped, as those get their diagnostics from
 * {@code textDocument/diagnostic}. Their result ids are not kept either, so that the server reports them
 * in full again once they are closed.
 */
public final class WorkspaceDiagnosticsPuller {

	private static final long DELAY_BETWEEN_PULLS_MS = 2000;
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
		pulls.entrySet().removeIf(pull -> {
			if (identifiers.contains(pull.getKey())) {
				return false;
			}
			pull.getValue().stop();
			return true;
		});
		for (final @Nullable String identifier : identifiers) {
			if (!pulls.containsKey(identifier)) {
				final var pull = new Pull(server, client, identifier);
				pulls.put(identifier, pull);
				pull.request();
			}
		}
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
		private volatile boolean stopped;
		private volatile @Nullable CompletableFuture<WorkspaceDiagnosticReport> pending;
		private int consecutiveErrors;

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
					return;
				}
			}
			CompletableFuture.delayedExecutor(DELAY_BETWEEN_PULLS_MS, TimeUnit.MILLISECONDS).execute(this::request);
		}

		private void accept(WorkspaceDocumentDiagnosticReport report) {
			if (report.isWorkspaceUnchangedDocumentDiagnosticReport()) {
				final var unchanged = report.getWorkspaceUnchangedDocumentDiagnosticReport();
				if (wrapper.isConnectedTo(LSPEclipseUtils.toUri(unchanged.getUri()))) {
					resultIds.remove(unchanged.getUri());
				} else {
					resultIds.put(unchanged.getUri(), unchanged.getResultId());
				}
				return;
			}
			final var full = report.getWorkspaceFullDocumentDiagnosticReport();
			final String uri = full.getUri();
			final String resultId = full.getResultId();
			if (wrapper.isConnectedTo(LSPEclipseUtils.toUri(uri))) {
				resultIds.remove(uri);
			} else {
				if (resultId != null) {
					resultIds.put(uri, resultId);
				} else {
					resultIds.remove(uri);
				}
				client.publishDiagnostics(new PublishDiagnosticsParams(uri, full.getItems()));
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
