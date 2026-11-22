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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServerWrapper;
import org.eclipse.lsp4e.operations.diagnostics.PullDiagnosticsReconcilingStrategy;
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
 * it registered {@code textDocument/diagnostic} with {@code workspaceDiagnostics}.
 * {@code workspace/diagnostic} is requested again once answered, after a short delay. The delay grows
 * while the answers bring nothing new, as each request tells the server all that is known already, and
 * is short again as soon as something is sent to the server or it asks for a refresh.
 * <p>
 * Reports for documents whose diagnostics are pulled with {@code textDocument/diagnostic} are dropped, as
 * those win. Nothing is remembered about them, so that the server reports them in full again once they
 * are not pulled anymore. Reports for documents that are not in the workspace are only remembered so far
 * as to tell the server that they are known, as there is nowhere to show them, until resources are added
 * to the workspace.
 * <p>
 * A server may not write the URI of a document as it is written here, on Windows for instance the letter
 * of the drive in the other case. A document that has a resource is therefore known by the URI of the
 * resource, whichever way the server writes it.
 * <p>
 * A server may answer a request little by little with partial results, and only complete it when it wants
 * to be asked again. Those are handled like complete answers.
 * <p>
 * The diagnostics of a document are the union of what each identifier last reported for it.
 * <p>
 * Everything is done one thing at a time by a queue, so that no caller ever waits for a request being
 * written to the server or for a report being handled, whatever locks it holds.
 */
public final class WorkspaceDiagnosticsPuller {

	private static final long DEFAULT_DELAY_BETWEEN_PULLS_MS = 2000;
	private static volatile long delayBetweenPulls = DEFAULT_DELAY_BETWEEN_PULLS_MS;
	private static final String PARTIAL_RESULT_TOKEN_PREFIX = "lsp4e-workspace-diagnostics-"; //$NON-NLS-1$
	/** The number of consecutive failed requests after which an identifier is not pulled anymore. */
	private static final int MAX_CONSECUTIVE_ERRORS = 5;

	private final LanguageServerWrapper wrapper;
	private final SerialExecutor queue = new SerialExecutor();
	private final IResourceChangeListener resourceListener = this::resourceChanged;
	private volatile @Nullable Session session;

	public WorkspaceDiagnosticsPuller(LanguageServerWrapper wrapper) {
		this.wrapper = wrapper;
	}

	/**
	 * For tests, which cannot wait as long as it is reasonable to in real use.
	 *
	 * @param delay the delay between an answer and the next request in milliseconds, or {@code null} for
	 *        the default
	 */
	public static void setDelayBetweenPulls(@Nullable Long delay) {
		delayBetweenPulls = delay == null ? DEFAULT_DELAY_BETWEEN_PULLS_MS : delay;
	}

	/** The delay grows up to this while the answers bring nothing new. */
	private static long maxDelayBetweenPulls() {
		return 4 * delayBetweenPulls;
	}

	/**
	 * Starts pulling from the given server, which must be initialized.
	 *
	 * @param client where to publish the diagnostics
	 */
	public synchronized void start(LanguageServer server, LanguageClient client) {
		stop();
		session = new Session(server, client);
		ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener,
				IResourceChangeEvent.POST_CHANGE);
		update();
	}

	/** Starts and stops pulls according to the current registrations of the server. */
	public void update() {
		run(session -> {
			final Set<@Nullable String> identifiers = wrapper
					.getRegistrationOptions(null, LSPMethods.TEXT_DOCUMENT_DIAGNOSTIC,
							DiagnosticRegistrationOptions.class)
					.stream() //
					.filter(DiagnosticRegistrationOptions::isWorkspaceDiagnostics) //
					.map(DiagnosticRegistrationOptions::getIdentifier) //
					.collect(Collectors.toSet());
			final var removed = new ArrayList<Pull>();
			session.pulls.entrySet().removeIf(pull -> {
				if (identifiers.contains(pull.getKey())) {
					return false;
				}
				pull.getValue().remove();
				removed.add(pull.getValue());
				return true;
			});
			// what the identifiers that are gone reported is not valid anymore
			removed.forEach(pull -> pull.known.forEach((uri, last) -> {
				if (last.resource != null) {
					publish(session, uri);
				}
			}));
			for (final @Nullable String identifier : identifiers) {
				if (!session.pulls.containsKey(identifier)) {
					final var pull = new Pull(session, identifier);
					session.pulls.put(identifier, pull);
					pull.request();
				}
			}
		});
	}

	/** Pulls again the identifiers that were given up on after repeated errors, and the others soon. */
	public void refresh() {
		run(session -> session.pulls.values().forEach(pull -> {
			pull.restart();
			pull.activity();
		}));
	}

	/**
	 * To call when something that may change the diagnostics is sent to the server, so that they are
	 * pulled again soon.
	 */
	public void activity() {
		run(session -> session.pulls.values().forEach(Pull::activity));
	}

	/**
	 * Handles the given progress if it is a partial result of one of the requests sent from here.
	 *
	 * @return whether the progress was handled
	 */
	public boolean notifyProgress(ProgressParams params) {
		final var token = params.getToken();
		final var value = params.getValue();
		if (token == null || value == null || !token.isLeft()
				|| !token.getLeft().startsWith(PARTIAL_RESULT_TOKEN_PREFIX) || !value.isRight()) {
			return false;
		}
		// a partial result that comes too late is of no interest to anyone else either
		run(session -> session.pulls.values().stream() //
				.filter(pull -> token.getLeft().equals(pull.partialResultToken)) //
				.forEach(pull -> pull.partialResult(value.getRight())));
		return true;
	}

	/**
	 * Forgets what was reported for the given document, so that the server reports it in full again. To
	 * call when the diagnostics of the document are not pulled with {@code textDocument/diagnostic}
	 * anymore.
	 */
	public void forget(URI uri) {
		run(session -> session.pulls.values().forEach(pull -> pull.forget(uri)));
	}

	/**
	 * The markers of a resource go away with it, whereas the server may not notice that it went away and
	 * came back: forgets what was reported for it, so that the server reports it in full again. So does a
	 * new resource for the documents that there was nowhere to show the diagnostics of.
	 */
	private void resourceChanged(IResourceChangeEvent event) {
		final IResourceDelta delta = event.getDelta();
		if (delta == null || session == null) {
			return;
		}
		final var gone = new HashSet<IPath>();
		final var added = new boolean[1];
		try {
			delta.accept(change -> {
				if ((change.getFlags() & IResourceDelta.OPEN) != 0) {
					// a project that was opened or closed
					added[0] = true;
				}
				if (change.getKind() == IResourceDelta.REMOVED
						|| (change.getFlags() & (IResourceDelta.REPLACED | IResourceDelta.OPEN)) != 0) {
					gone.add(change.getFullPath());
					return false;
				}
				if (change.getKind() == IResourceDelta.ADDED) {
					added[0] = true;
					return false;
				}
				return true;
			});
		} catch (CoreException ex) {
			LanguageServerPlugin.logError(ex);
		}
		if (!gone.isEmpty() || added[0]) {
			run(session -> session.pulls.values().forEach(pull -> pull.forgetResources(gone, added[0])));
		}
	}

	/**
	 * Stops pulling. The requests that are not answered yet are left alone, as the server is stopped too and
	 * may not even read what is sent to it anymore.
	 */
	public synchronized void stop() {
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
		final Session session = this.session;
		this.session = null;
		if (session != null) {
			session.stopped = true;
		}
	}

	/**
	 * Stops pulling from the given server, if it is the one that is pulled from: a server that is stopped
	 * late must not stop the pulls of the one that was started since.
	 *
	 * @param server may be {@code null}, as a server that failed to start is
	 */
	public synchronized void stop(@Nullable LanguageServer server) {
		final Session session = this.session;
		if (session != null && session.server == server) {
			stop();
		}
	}

	/** Runs the given task in the queue, unless pulling is or gets stopped before its turn. */
	private void run(Consumer<Session> task) {
		final Session session = this.session;
		if (session != null) {
			queue.execute(() -> {
				if (!session.stopped) {
					task.accept(session);
				}
			});
		}
	}

	/**
	 * @return whether the diagnostics of the given document are pulled with
	 *         {@code textDocument/diagnostic}, which those of a document that is open in an editor
	 *         without support for it are not
	 */
	private boolean isPulledAsDocument(URI uri) {
		return wrapper.isConnectedTo(uri) && PullDiagnosticsReconcilingStrategy.isPulling(wrapper, uri);
	}

	private void publish(Session session, URI uri) {
		if (isPulledAsDocument(uri)) {
			return;
		}
		final var diagnostics = new ArrayList<Diagnostic>();
		for (final Pull pull : session.pulls.values()) {
			final Known known = pull.known.get(uri);
			if (known != null) {
				diagnostics.addAll(known.diagnostics);
			}
		}
		session.client.publishDiagnostics(new PublishDiagnosticsParams(uri.toString(), diagnostics));
	}

	/** Runs tasks one at a time, in the order they were given. */
	private static final class SerialExecutor implements Executor {
		private CompletableFuture<@Nullable Void> last = CompletableFuture.completedFuture(null);

		@Override
		public synchronized void execute(Runnable task) {
			last = last.handleAsync((result, failure) -> {
				try {
					task.run();
				} catch (RuntimeException ex) {
					LanguageServerPlugin.logError(ex);
				}
				return null;
			});
		}
	}

	/** What is pulled from a server from its start to its stop. */
	private static final class Session {
		final LanguageServer server;
		final LanguageClient client;
		/** The running pulls by identifier, which may be {@code null}. Only used from the queue. */
		final Map<@Nullable String, Pull> pulls = new HashMap<>();
		volatile boolean stopped;

		Session(LanguageServer server, LanguageClient client) {
			this.server = server;
			this.client = client;
		}
	}

	/**
	 * What an identifier last reported for a document.
	 *
	 * @param uri the URI of the document as the server writes it
	 * @param resultId the result id of the last report, if the server sent one
	 * @param resource where the diagnostics are shown, or {@code null} if there is nowhere to
	 * @param diagnostics none if there is nowhere to show them
	 */
	private record Known(String uri, @Nullable String resultId, @Nullable IResource resource,
			List<Diagnostic> diagnostics) {
	}

	/** The pulls of one identifier. Only used from the queue. */
	private final class Pull {
		private final Session session;
		private final @Nullable String identifier;
		/** By the URI that {@link #key(String)} gives for the document. */
		private final Map<URI, Known> known = new HashMap<>();
		/** The URI a document is known by, by the URI the server wrote. */
		private final Map<String, URI> keys = new HashMap<>();
		/**
		 * The documents that the server need not report in answer to the last request: those it was told
		 * the result id of, and those it reported already.
		 */
		private final Set<URI> settled = new HashSet<>();
		private boolean removed;
		private boolean gaveUp;
		private @Nullable CompletableFuture<WorkspaceDiagnosticReport> pending;
		private int consecutiveErrors;
		/** Whether an answer to the last request changed the diagnostics of a document. */
		private boolean changed;
		/** Whether something was sent to the server since the last request. */
		private boolean activity;
		private long delay = delayBetweenPulls;
		/** The next request, while it is scheduled. */
		private @Nullable CompletableFuture<@Nullable Void> timer;
		/** When the scheduled request is due, as {@link System#nanoTime()}. */
		private long due;
		/** The token of the last request, with which the server may send partial results. */
		private @Nullable String partialResultToken;
		/** The reports that could not be handled, which a server sends again and again, to log them once. */
		private final Set<String> failures = new HashSet<>();

		Pull(Session session, @Nullable String identifier) {
			this.session = session;
			this.identifier = identifier;
		}

		private boolean isStopped() {
			return removed || session.stopped;
		}

		/**
		 * @param serverUri the URI of a document as the server wrote it
		 * @return the URI the document is known by, which is that of its resource if it has one
		 */
		private URI key(String serverUri) {
			URI key = keys.get(serverUri);
			if (key == null) {
				key = LSPEclipseUtils.toUri(serverUri);
				final IResource resource = LSPEclipseUtils.findResourceFor(key);
				final URI resourceUri = resource == null ? null : LSPEclipseUtils.toUri(resource);
				if (resourceUri != null) {
					key = resourceUri;
				}
				keys.put(serverUri, key);
			}
			return key;
		}

		void request() {
			if (isStopped() || pending != null) {
				return;
			}
			final var timer = this.timer;
			this.timer = null;
			if (timer != null) {
				// if it is not what runs this
				timer.cancel(false);
			}
			activity = false;
			changed = false;
			settled.clear();
			// what is known of a document that is pulled as a document since is not updated anymore
			known.keySet().removeIf(WorkspaceDiagnosticsPuller.this::isPulledAsDocument);
			final var previousResultIds = new ArrayList<PreviousResultId>();
			known.forEach((uri, last) -> {
				final String resultId = last.resultId;
				if (resultId != null) {
					previousResultIds.add(new PreviousResultId(last.uri, resultId));
					settled.add(uri);
				}
			});
			final var params = new WorkspaceDiagnosticParams(previousResultIds);
			params.setIdentifier(identifier);
			final String token = PARTIAL_RESULT_TOKEN_PREFIX + UUID.randomUUID();
			partialResultToken = token;
			params.setPartialResultToken(token);
			CompletableFuture<WorkspaceDiagnosticReport> sending;
			try {
				sending = session.server.getWorkspaceService().diagnostic(params);
			} catch (RuntimeException ex) {
				sending = CompletableFuture.failedFuture(ex);
			}
			final var request = sending;
			pending = request;
			request.whenCompleteAsync((report, ex) -> completed(request, report, ex), queue);
		}

		private void completed(CompletableFuture<WorkspaceDiagnosticReport> request,
				@Nullable WorkspaceDiagnosticReport report, @Nullable Throwable ex) {
			if (pending != request) {
				return;
			}
			pending = null;
			if (isStopped()) {
				return;
			}
			if (ex == null) {
				consecutiveErrors = 0;
				if (report != null) {
					accept(report.getItems());
				}
			} else if (!CancellationUtil.isRequestCancelledException(ex)) {
				LanguageServerPlugin.logError(ex);
				if (++consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
					gaveUp = true;
					LanguageServerPlugin.logWarning("Workspace diagnostics" //$NON-NLS-1$
							+ (identifier == null ? "" : " with identifier " + identifier) //$NON-NLS-1$ //$NON-NLS-2$
							+ " are not pulled anymore from " + wrapper.serverDefinition.label //$NON-NLS-1$
							+ " after repeated failures, until it asks for a refresh or is started again"); //$NON-NLS-1$
					return;
				}
			}
			delay = changed || activity || ex != null ? delayBetweenPulls
					: Math.min(delay * 2, maxDelayBetweenPulls());
			requestAfter(delay);
		}

		private void requestAfter(long delay) {
			final var timer = this.timer;
			if (timer != null) {
				timer.cancel(false);
			}
			due = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
			// a request that is cancelled before its turn does not run
			this.timer = CompletableFuture.runAsync(this::request,
					CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, queue));
		}

		void activity() {
			activity = true;
			if (timer != null) {
				// waiting for long as nothing changed, which may not hold anymore. The request is never
				// put off, as it would be again and again while there is activity.
				delay = delayBetweenPulls;
				if (due - System.nanoTime() > TimeUnit.MILLISECONDS.toNanos(delay)) {
					requestAfter(delay);
				}
			}
		}

		/** Forgets what was reported for the given document, so that the server reports it in full again. */
		void forget(URI uri) {
			known.remove(uri);
			final var pending = this.pending;
			if (pending != null && settled.contains(uri)) {
				// a server may take as long as it likes to answer, and will not report the document in
				// answer to this request: the next one is sent once this one is cancelled
				pending.cancel(true);
			}
			activity();
		}

		void partialResult(Object value) {
			if (isStopped()) {
				return;
			}
			try {
				final var gson = JsonUtil.LSP4J_GSON;
				final var result = gson.fromJson(gson.toJsonTree(value), WorkspaceDiagnosticReportPartialResult.class);
				if (result != null) {
					accept(result.getItems());
				}
			} catch (RuntimeException ex) {
				LanguageServerPlugin.logError(ex);
			}
		}

		void restart() {
			if (gaveUp) {
				gaveUp = false;
				consecutiveErrors = 0;
				request();
			}
		}

		private void accept(@Nullable List<WorkspaceDocumentDiagnosticReport> reports) {
			if (reports == null) {
				return;
			}
			for (final WorkspaceDocumentDiagnosticReport report : reports) {
				try {
					accept(report);
				} catch (RuntimeException ex) {
					// such as a URI that is none: the other reports are fine
					if (failures.add(String.valueOf(ex.getMessage()))) {
						LanguageServerPlugin.logError(ex);
					}
				}
			}
		}

		private void accept(WorkspaceDocumentDiagnosticReport report) {
			if (report.isWorkspaceUnchangedDocumentDiagnosticReport()) {
				final var unchanged = report.getWorkspaceUnchangedDocumentDiagnosticReport();
				final URI uri = key(unchanged.getUri());
				settled.add(uri);
				final Known last = known.get(uri);
				if (isPulledAsDocument(uri)) {
					known.remove(uri);
				} else if (last != null) {
					// nothing changed, only the result id to send next time may have
					known.put(uri, new Known(last.uri, unchanged.getResultId(), last.resource, last.diagnostics));
				}
				return;
			}
			final var full = report.getWorkspaceFullDocumentDiagnosticReport();
			final URI uri = key(full.getUri());
			settled.add(uri);
			if (isPulledAsDocument(uri)) {
				known.remove(uri);
				return;
			}
			final Known last = known.get(uri);
			IResource resource = last == null ? null : last.resource;
			if (last == null || resource != null && !resource.isAccessible()) {
				resource = LSPEclipseUtils.findResourceFor(uri);
			}
			if (resource == null || !resource.isAccessible()) {
				// only so that the server does not send the diagnostics again each time
				known.put(uri, new Known(full.getUri(), full.getResultId(), null, List.of()));
				return;
			}
			final List<Diagnostic> items = full.getItems();
			final List<Diagnostic> diagnostics = items == null ? List.of() : items;
			known.put(uri, new Known(full.getUri(), full.getResultId(), resource, diagnostics));
			if (last == null || !last.diagnostics.equals(diagnostics)) {
				changed = true;
				publish(session, uri);
			}
		}

		/**
		 * Forgets what was reported for the resources at the given paths and below.
		 *
		 * @param withoutResource whether to also forget what was reported for documents without a resource
		 */
		void forgetResources(Set<IPath> paths, boolean withoutResource) {
			// a document may have a resource now, or none anymore
			keys.clear();
			final boolean forgot = known.values().removeIf(last -> {
				final IResource resource = last.resource;
				if (resource == null) {
					return withoutResource;
				}
				for (IPath path = resource.getFullPath(); !path.isEmpty(); path = path
						.removeLastSegments(1)) {
					if (paths.contains(path)) {
						return true;
					}
				}
				return false;
			});
			if (forgot) {
				activity();
			}
		}

		void remove() {
			removed = true;
			final var pending = this.pending;
			if (pending != null) {
				pending.cancel(true);
			}
		}
	}
}
