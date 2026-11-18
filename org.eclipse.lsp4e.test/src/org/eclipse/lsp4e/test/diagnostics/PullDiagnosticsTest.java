/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4e.test.diagnostics;

import static org.eclipse.lsp4e.test.utils.TestUtils.waitForAndAssertCondition;
import static org.eclipse.lsp4e.test.utils.TestUtils.waitForCondition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4e.internal.LSPMethods;
import org.eclipse.lsp4e.operations.diagnostics.LSPDiagnosticsToMarkers;
import org.eclipse.lsp4e.operations.diagnostics.PullDiagnosticsReconcilingStrategy;
import org.eclipse.lsp4e.test.utils.AbstractTestWithProject;
import org.eclipse.lsp4e.test.utils.TestUtils;
import org.eclipse.lsp4e.tests.mock.MockLanguageServer;
import org.eclipse.lsp4e.tests.mock.MockLanguageServerFactory;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRegistrationOptions;
import org.eclipse.lsp4j.DiagnosticServerCapabilities;
import org.eclipse.lsp4j.DocumentDiagnosticParams;
import org.eclipse.lsp4j.DocumentFilter;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PreviousResultId;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentServerCapabilities;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkspaceDiagnosticParams;
import org.eclipse.lsp4j.WorkspaceDiagnosticReport;
import org.eclipse.lsp4j.WorkspaceDiagnosticReportPartialResult;
import org.eclipse.lsp4j.WorkspaceDocumentDiagnosticReport;
import org.eclipse.lsp4j.WorkspaceFullDocumentDiagnosticReport;
import org.eclipse.lsp4j.WorkspaceUnchangedDocumentDiagnosticReport;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.ui.IEditorPart;
import org.junit.jupiter.api.Test;

public class PullDiagnosticsTest extends AbstractTestWithProject {

	@Test
	public void testDiagnosticProviderEnablesPullDiagnostics(MockLanguageServerFactory factory) throws Exception {
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setIdentifier("test-identifier")));

		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		TestUtils.openTextViewer(testFile);

		waitForAndAssertCondition(5_000, () -> !getDiagnosticRequests(factory).isEmpty());
		DocumentDiagnosticParams request = getDiagnosticRequests(factory).get(0);
		assertEquals("test-identifier", request.getIdentifier());
		assertTrue(request.getTextDocument().getUri().endsWith(testFile.getName()));
	}

	@Test
	public void testTextDocumentDiagnosticCapabilityAloneDoesNotEnablePullDiagnostics(MockLanguageServerFactory factory)
			throws Exception {
		factory.withCapabilities(() -> {
			ServerCapabilities capabilities = MockLanguageServer.defaultServerCapabilities();
			final var textDocument = new TextDocumentServerCapabilities();
			final var diagnostic = new DiagnosticServerCapabilities();
			diagnostic.setMarkupMessageSupport(true);
			textDocument.setDiagnostic(diagnostic);
			capabilities.setTextDocument(textDocument);
			return capabilities;
		});

		assertNoDiagnosticRequestAfterEdit(factory);
	}

	@Test
	public void testMatchingDocumentSelector(MockLanguageServerFactory factory) throws Exception {
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> {
			final var filter = new DocumentFilter();
			filter.setLanguage("lspt");
			filter.setScheme("file");
			filter.setPattern("**/*.lspt");
			provider.setDocumentSelector(List.of(filter));
		}));

		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		TestUtils.openTextViewer(testFile);

		waitForAndAssertCondition(5_000, () -> !getDiagnosticRequests(factory).isEmpty());
	}

	@Test
	public void testNonMatchingDocumentSelector(MockLanguageServerFactory factory) throws Exception {
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> {
			final var filter = new DocumentFilter();
			filter.setLanguage("otherLanguage");
			provider.setDocumentSelector(List.of(filter));
		}));

		assertNoDiagnosticRequestAfterEdit(factory);
	}

	@Test
	public void testDynamicRegistrationsWithDistinctIdentifiers(MockLanguageServerFactory factory) throws Exception {
		factory.withConfiguration((idx, server) -> server.getTextDocumentService().setPullDiagnostics(
				params -> CompletableFuture.completedFuture(List.of(diagnostic("from " + params.getIdentifier())))));
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		registerSyntaxAndSemanticDiagnostics(factory, testFile);

		TestUtils.openTextViewer(testFile);

		waitForAndAssertCondition(5_000, () -> getDiagnosticRequests(factory).stream()
				.map(DocumentDiagnosticParams::getIdentifier).collect(Collectors.toSet())
				.equals(Set.of("syntax", "semantic")));
		// the reports of all identifiers are published together, rather than replacing each other
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from syntax", "from semantic")));
	}

	@Test
	public void testFailingIdentifierDoesNotSuppressOthers(MockLanguageServerFactory factory) throws Exception {
		final var semanticFails = new AtomicBoolean(true);
		final var syntaxMessage = new AtomicReference<>("syntax 1");
		factory.withConfiguration((idx, server) -> server.getTextDocumentService().setPullDiagnostics(params -> {
			if ("syntax".equals(params.getIdentifier())) {
				return CompletableFuture.completedFuture(List.of(diagnostic(syntaxMessage.get())));
			}
			if (semanticFails.get()) {
				return CompletableFuture.failedFuture(serverCancelled());
			}
			return CompletableFuture.completedFuture(List.of(diagnostic("semantic")));
		}));
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		registerSyntaxAndSemanticDiagnostics(factory, testFile);

		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		// the failed identifier does not prevent the successful one from being published
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("syntax 1")));

		semanticFails.set(false);
		viewer.getDocument().replace(0, 0, "a");
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("syntax 1", "semantic")));

		semanticFails.set(true);
		syntaxMessage.set("syntax 2");
		viewer.getDocument().replace(0, 0, "b");
		// the failed identifier keeps its last diagnostics instead of losing them
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("syntax 2", "semantic")));
	}

	@Test
	public void testLateResponseForPreviousDocumentDoesNotLeakIntoNewDocument(MockLanguageServerFactory factory)
			throws Exception {
		IFile fileA = TestUtils.createUniqueTestFile(project, "");
		IFile fileB = TestUtils.createUniqueTestFile(project, "");
		final var semanticForA = new CompletableFuture<List<Diagnostic>>();
		final var syntaxMessageForB = new AtomicReference<>("B syntax 1");
		factory.withConfiguration((idx, server) -> server.getTextDocumentService().setPullDiagnostics(params -> {
			final boolean isA = params.getTextDocument().getUri().endsWith(fileA.getName());
			if ("syntax".equals(params.getIdentifier())) {
				return CompletableFuture.completedFuture(List.of(diagnostic(isA ? "A syntax" : syntaxMessageForB.get())));
			}
			return isA ? semanticForA : CompletableFuture.failedFuture(serverCancelled());
		}));
		registerSyntaxAndSemanticDiagnostics(factory, fileA);
		IDocument documentA = LSPEclipseUtils.getDocument(fileA);
		IDocument documentB = LSPEclipseUtils.getDocument(fileB);
		assertNotNull(documentA);
		assertNotNull(documentB);

		final var strategy = new PullDiagnosticsReconcilingStrategy();
		strategy.setDocument(documentA);
		strategy.install(null);
		try {
			waitForAndAssertCondition(5_000, () -> getDiagnosticRequests(factory).stream().anyMatch(
					p -> "semantic".equals(p.getIdentifier()) && p.getTextDocument().getUri().endsWith(fileA.getName())));

			// switch the input to B while the semantic request for A is still pending
			strategy.setDocument(documentB);
			strategy.reconcile(null);
			waitForAndAssertCondition(5_000, () -> getMarkerMessages(fileB).equals(Set.of("B syntax 1")));

			semanticForA.complete(List.of(diagnostic("A semantic")));
			waitForAndAssertCondition(5_000, () -> getMarkerMessages(fileA).contains("A semantic"));

			syntaxMessageForB.set("B syntax 2");
			strategy.reconcile(null);
			waitForAndAssertCondition(5_000, () -> getMarkerMessages(fileB).contains("B syntax 2"));
			// the semantic identifier still fails for B and must not fall back to A's report
			assertEquals(Set.of("B syntax 2"), getMarkerMessages(fileB));
		} finally {
			strategy.uninstall();
		}
	}

	@Test
	public void testRefreshPullsOpenDocumentAgain(MockLanguageServerFactory factory) throws Exception {
		final var message = new AtomicReference<>("before refresh");
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> {}));
		factory.withConfiguration((idx, server) -> server.getTextDocumentService()
				.setPullDiagnostics(params -> CompletableFuture.completedFuture(List.of(diagnostic(message.get())))));

		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		TestUtils.openTextViewer(testFile);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("before refresh")));

		message.set("after refresh");
		factory.getServer().getRemoteProxy().refreshDiagnostics().get(5, TimeUnit.SECONDS);

		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("after refresh")));
		// the server did not announce workspace diagnostics, so it must not be asked for them
		assertEquals(List.of(), factory.getServer().getWorkspaceService().getDiagnosticRequests());
	}

	@Test
	public void testWorkspaceDiagnosticsArePulledRepeatedly(MockLanguageServerFactory factory) throws Exception {
		IFile openFile = TestUtils.createUniqueTestFile(project, "");
		IFile closedFile = TestUtils.createUniqueTestFile(project, "");
		final var message = new AtomicReference<>("workspace 1");
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> {
			provider.setIdentifier("test-identifier");
			provider.setWorkspaceDiagnostics(true);
		}));
		factory.withConfiguration((idx, server) -> {
			server.getTextDocumentService().setPullDiagnostics(
					params -> CompletableFuture.completedFuture(List.of(diagnostic("from document pull"))));
			server.getWorkspaceService().setWorkspaceDiagnostics(
					params -> CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(List.of( //
							workspaceReport(openFile, "from workspace pull", "open " + message.get()),
							workspaceReport(closedFile, message.get(), "closed " + message.get())))));
		});

		TestUtils.openTextViewer(openFile);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(openFile).equals(Set.of("from document pull")));
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(closedFile).equals(Set.of("workspace 1")));

		// the server is asked again without anything happening in the IDE
		message.set("workspace 2");
		waitForAndAssertCondition(15_000, () -> getMarkerMessages(closedFile).equals(Set.of("workspace 2")));
		// the document pull wins for a document that is open
		assertEquals(Set.of("from document pull"), getMarkerMessages(openFile));

		final String closedUri = LSPEclipseUtils.toUri(closedFile).toString();
		waitForAndAssertCondition(15_000, () -> getWorkspaceDiagnosticRequests(factory).stream()
				.anyMatch(request -> request.getPreviousResultIds()
						.equals(List.of(new PreviousResultId(closedUri, "closed workspace 2")))));
		List<WorkspaceDiagnosticParams> requests = getWorkspaceDiagnosticRequests(factory);
		assertEquals(List.of(), requests.get(0).getPreviousResultIds());
		assertEquals(Set.of("test-identifier"),
				requests.stream().map(WorkspaceDiagnosticParams::getIdentifier).collect(Collectors.toSet()));
	}

	@Test
	public void testUnchangedWorkspaceReportKeepsDiagnostics(MockLanguageServerFactory factory) throws Exception {
		IFile openFile = TestUtils.createUniqueTestFile(project, "");
		IFile closedFile = TestUtils.createUniqueTestFile(project, "");
		final String closedUri = LSPEclipseUtils.toUri(closedFile).toString();
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> server.getWorkspaceService().setWorkspaceDiagnostics(params -> {
			final var report = params.getPreviousResultIds().isEmpty()
					? workspaceReport(closedFile, "from workspace pull", "result 1")
					: new WorkspaceDocumentDiagnosticReport(
							new WorkspaceUnchangedDocumentDiagnosticReport("result 2", closedUri, null));
			return CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(List.of(report)));
		}));

		TestUtils.openTextViewer(openFile);

		waitForAndAssertCondition(10_000, () -> getWorkspaceDiagnosticRequests(factory).stream()
				.anyMatch(request -> request.getPreviousResultIds()
						.equals(List.of(new PreviousResultId(closedUri, "result 2")))));
		assertEquals(Set.of("from workspace pull"), getMarkerMessages(closedFile));
	}

	@Test
	public void testResultIdOfDocumentOpenedAfterItWasReportedIsNotSent(MockLanguageServerFactory factory)
			throws Exception {
		IFile otherFile = TestUtils.createUniqueTestFile(project, "");
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> server.getWorkspaceService()
				.setWorkspaceDiagnostics(params -> CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(
						List.of(workspaceReport(testFile, "from workspace pull", "result"))))));

		TestUtils.openTextViewer(otherFile);
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).stream()
				.anyMatch(request -> !request.getPreviousResultIds().isEmpty()));

		// what is reported for the document is ignored from now on, so its result id is not the last one
		TestUtils.openTextViewer(testFile);
		final int requestsBefore = getWorkspaceDiagnosticRequests(factory).size();
		waitForAndAssertCondition(15_000, () -> getWorkspaceDiagnosticRequests(factory).stream().skip(requestsBefore)
				.anyMatch(request -> request.getPreviousResultIds().isEmpty()));
	}

	@Test
	public void testWorkspaceDiagnosticsFollowDynamicRegistration(MockLanguageServerFactory factory) throws Exception {
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		IDocument document = LSPEclipseUtils.getDocument(testFile);
		assertNotNull(document);
		LanguageServers.forDocument(document).collectAll(ls -> CompletableFuture.completedFuture(ls))
				.get(5, TimeUnit.SECONDS);
		assertFalse(waitForCondition(3_000, () -> !getWorkspaceDiagnosticRequests(factory).isEmpty()));

		final var options = new DiagnosticRegistrationOptions(false, true);
		options.setIdentifier("dynamic");
		final var client = factory.getServer().getRemoteProxy();
		client.registerCapability(new RegistrationParams(
				List.of(new Registration("registration", LSPMethods.TEXT_DOCUMENT_DIAGNOSTIC, options))))
				.get(1, TimeUnit.SECONDS);
		waitForAndAssertCondition(10_000, () -> getWorkspaceDiagnosticRequests(factory).size() >= 2);
		assertEquals("dynamic", getWorkspaceDiagnosticRequests(factory).get(0).getIdentifier());

		client.unregisterCapability(new UnregistrationParams(
				List.of(new Unregistration("registration", LSPMethods.TEXT_DOCUMENT_DIAGNOSTIC))))
				.get(1, TimeUnit.SECONDS);
		final int requestsAtUnregistration = getWorkspaceDiagnosticRequests(factory).size();
		assertFalse(waitForCondition(5_000,
				() -> getWorkspaceDiagnosticRequests(factory).size() > requestsAtUnregistration));
	}

	@Test
	public void testWorkspaceDiagnosticsGiveUpOnRepeatedErrors(MockLanguageServerFactory factory) throws Exception {
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> server.getWorkspaceService()
				.setWorkspaceDiagnostics(params -> CompletableFuture.failedFuture(new ResponseErrorException(
						new ResponseError(ResponseErrorCode.InternalError, "workspace diagnostics failed", null)))));

		TestUtils.openTextViewer(TestUtils.createUniqueTestFile(project, ""));

		// the first request and five more attempts, two seconds apart
		waitForAndAssertCondition(20_000, () -> getWorkspaceDiagnosticRequests(factory).size() == 6);
		assertFalse(waitForCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).size() > 6));

		// a refresh is taken as a sign that the server recovered
		factory.getServer().getRemoteProxy().refreshDiagnostics().get(5, TimeUnit.SECONDS);
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).size() > 6);
	}

	@Test
	public void testWorkspaceDiagnosticsOfSeveralIdentifiersAreMerged(MockLanguageServerFactory factory)
			throws Exception {
		IFile openFile = TestUtils.createUniqueTestFile(project, "");
		IFile closedFile = TestUtils.createUniqueTestFile(project, "");
		factory.withConfiguration((idx, server) -> server.getWorkspaceService()
				.setWorkspaceDiagnostics(params -> CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(List.of( //
						workspaceReport(closedFile, "from " + params.getIdentifier(), params.getIdentifier()),
						// a document that is not in the workspace is ignored
						new WorkspaceDocumentDiagnosticReport(new WorkspaceFullDocumentDiagnosticReport(
								List.of(diagnostic("nowhere to show")), "file:///not/in/the/workspace.lspt", null)))))));
		TestUtils.openTextViewer(openFile);
		IDocument document = LSPEclipseUtils.getDocument(openFile);
		assertNotNull(document);
		LanguageServers.forDocument(document).collectAll(ls -> CompletableFuture.completedFuture(ls))
				.get(5, TimeUnit.SECONDS);

		final var registrations = new ArrayList<Registration>();
		for (String identifier : List.of("syntax", "semantic")) {
			final var options = new DiagnosticRegistrationOptions(false, true);
			options.setIdentifier(identifier);
			registrations.add(new Registration(identifier, LSPMethods.TEXT_DOCUMENT_DIAGNOSTIC, options));
		}
		final var client = factory.getServer().getRemoteProxy();
		client.registerCapability(new RegistrationParams(registrations)).get(1, TimeUnit.SECONDS);
		// the reports of all identifiers are published together, rather than replacing each other
		waitForAndAssertCondition(5_000,
				() -> getMarkerMessages(closedFile).equals(Set.of("from syntax", "from semantic")));

		// what an identifier reported goes away with its registration
		client.unregisterCapability(new UnregistrationParams(
				List.of(new Unregistration("syntax", LSPMethods.TEXT_DOCUMENT_DIAGNOSTIC)))).get(1, TimeUnit.SECONDS);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(closedFile).equals(Set.of("from semantic")));
	}

	@Test
	public void testWorkspaceDiagnosticsTakeOverWhenEditorCloses(MockLanguageServerFactory factory) throws Exception {
		IFile otherFile = TestUtils.createUniqueTestFile(project, "");
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		final String testUri = LSPEclipseUtils.toUri(testFile).toString();
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> {
			server.getTextDocumentService().setPullDiagnostics(
					params -> CompletableFuture.completedFuture(List.of(diagnostic("from document pull"))));
			// like a server that only reports what the client does not know yet
			server.getWorkspaceService().setWorkspaceDiagnostics(params -> CompletableFuture
					.completedFuture(new WorkspaceDiagnosticReport(params.getPreviousResultIds().stream()
							.anyMatch(previous -> testUri.equals(previous.getUri())) ? List.of()
									: List.of(workspaceReport(testFile, "from workspace pull", "result")))));
		});

		// keeps the server running once the editor of the test file is closed
		TestUtils.openTextViewer(otherFile);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from workspace pull")));
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).stream()
				.anyMatch(request -> !request.getPreviousResultIds().isEmpty()));

		IEditorPart editor = TestUtils.openEditor(testFile);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from document pull")));
		// the document pull keeps winning while the document is open
		assertFalse(waitForCondition(3_000, () -> !getMarkerMessages(testFile).equals(Set.of("from document pull"))));

		TestUtils.closeEditor(editor, false);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from workspace pull")));
	}

	@Test
	public void testWorkspaceDiagnosticsArePulledLessOftenWhileNothingChanges(MockLanguageServerFactory factory)
			throws Exception {
		IFile openFile = TestUtils.createUniqueTestFile(project, "");
		IFile closedFile = TestUtils.createUniqueTestFile(project, "");
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> server.getWorkspaceService()
				.setWorkspaceDiagnostics(params -> CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(
						params.getPreviousResultIds().isEmpty() ? List.of(workspaceReport(closedFile, "same", "result"))
								: List.of()))));

		ITextViewer viewer = TestUtils.openTextViewer(openFile);
		waitForAndAssertCondition(5_000, () -> !getWorkspaceDiagnosticRequests(factory).isEmpty());
		final long start = System.currentTimeMillis();
		// 2 seconds after the first answer, which brought something new, then 4 and 8 seconds
		waitForAndAssertCondition(20_000, () -> getWorkspaceDiagnosticRequests(factory).size() == 4);
		assertTrue(System.currentTimeMillis() - start > 12_000);

		// the next request would only come after 16 seconds, unless something is sent to the server
		viewer.getDocument().replace(0, 0, "a");
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).size() == 5);
	}

	@Test
	public void testPartialWorkspaceDiagnosticResults(MockLanguageServerFactory factory) throws Exception {
		IFile openFile = TestUtils.createUniqueTestFile(project, "");
		IFile closedFile = TestUtils.createUniqueTestFile(project, "");
		final String closedUri = LSPEclipseUtils.toUri(closedFile).toString();
		final var request = new CompletableFuture<WorkspaceDiagnosticReport>();
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		// like a server that keeps the request open and reports through partial results
		factory.withConfiguration(
				(idx, server) -> server.getWorkspaceService().setWorkspaceDiagnostics(params -> request));

		TestUtils.openTextViewer(openFile);
		waitForAndAssertCondition(5_000, () -> !getWorkspaceDiagnosticRequests(factory).isEmpty());
		final var token = getWorkspaceDiagnosticRequests(factory).get(0).getPartialResultToken();
		assertNotNull(token);

		final var client = factory.getServer().getRemoteProxy();
		for (String message : List.of("partial 1", "partial 2")) {
			client.notifyProgress(new ProgressParams(token, Either.forRight(new WorkspaceDiagnosticReportPartialResult(
					List.of(workspaceReport(closedFile, message, "result " + message))))));
			waitForAndAssertCondition(5_000, () -> getMarkerMessages(closedFile).equals(Set.of(message)));
		}

		// the server is asked again once it completes the request, with what the partial results told
		request.complete(new WorkspaceDiagnosticReport(List.of()));
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).size() == 2);
		assertEquals(List.of(new PreviousResultId(closedUri, "result partial 2")),
				getWorkspaceDiagnosticRequests(factory).get(1).getPreviousResultIds());
		assertEquals(Set.of("partial 2"), getMarkerMessages(closedFile));
	}

	private static WorkspaceDocumentDiagnosticReport workspaceReport(IFile file, String message, String resultId) {
		final var report = new WorkspaceFullDocumentDiagnosticReport(List.of(diagnostic(message)),
				LSPEclipseUtils.toUri(file).toString(), null);
		report.setResultId(resultId);
		return new WorkspaceDocumentDiagnosticReport(report);
	}

	private static List<WorkspaceDiagnosticParams> getWorkspaceDiagnosticRequests(MockLanguageServerFactory factory) {
		return factory.getServer().getWorkspaceService().getDiagnosticRequests();
	}

	private static ResponseErrorException serverCancelled() {
		return new ResponseErrorException(
				new ResponseError(ResponseErrorCode.ServerCancelled, "semantic analysis cancelled", null));
	}

	private static void registerSyntaxAndSemanticDiagnostics(MockLanguageServerFactory factory, IFile testFile)
			throws Exception {
		IDocument document = LSPEclipseUtils.getDocument(testFile);
		assertNotNull(document);
		// wait until the server is initialized and connected, so it can send registrations
		LanguageServers.forDocument(document).collectAll(ls -> CompletableFuture.completedFuture(ls))
				.get(5, TimeUnit.SECONDS);

		// like Roslyn: several registrations without document selector, one per identifier
		final var registrations = new ArrayList<Registration>();
		for (String identifier : List.of("syntax", "semantic")) {
			final var options = new DiagnosticRegistrationOptions(true, false);
			options.setIdentifier(identifier);
			registrations.add(new Registration(identifier, LSPMethods.TEXT_DOCUMENT_DIAGNOSTIC, options));
		}
		factory.getServer().getRemoteProxy().registerCapability(new RegistrationParams(registrations)).get(1,
				TimeUnit.SECONDS);
	}

	private static Diagnostic diagnostic(String message) {
		return new Diagnostic(new Range(new Position(0, 0), new Position(0, 1)), message);
	}

	private static Set<String> getMarkerMessages(IFile file) throws CoreException {
		return Arrays.stream(file.findMarkers(LSPDiagnosticsToMarkers.LS_DIAGNOSTIC_MARKER_TYPE, false, IResource.DEPTH_ZERO))
				.map(marker -> marker.getAttribute(IMarker.MESSAGE, "")).collect(Collectors.toSet());
	}

	private void assertNoDiagnosticRequestAfterEdit(MockLanguageServerFactory factory) throws Exception {
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		viewer.getDocument().replace(0, 0, "Hello");
		waitForAndAssertCondition(5_000, () -> !factory.getServer().getDidChangeEvents().isEmpty());

		assertFalse(waitForCondition(2_000, () -> !getDiagnosticRequests(factory).isEmpty()));
	}

	private static ServerCapabilities withDiagnosticProvider(Consumer<DiagnosticRegistrationOptions> customizer) {
		ServerCapabilities capabilities = MockLanguageServer.defaultServerCapabilities();
		final var provider = new DiagnosticRegistrationOptions(false, false);
		customizer.accept(provider);
		capabilities.setDiagnosticProvider(provider);
		return capabilities;
	}

	private static List<DocumentDiagnosticParams> getDiagnosticRequests(MockLanguageServerFactory factory) {
		return factory.getServer().getTextDocumentService().getDiagnosticRequests();
	}
}
