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

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
import org.eclipse.lsp4e.internal.WorkspaceDiagnosticsPuller;
import org.eclipse.lsp4e.operations.diagnostics.LSPDiagnosticsToMarkers;
import org.eclipse.lsp4e.operations.diagnostics.PullDiagnosticsReconcilingStrategy;
import org.eclipse.lsp4e.test.utils.AbstractTestWithProject;
import org.eclipse.lsp4e.test.utils.TestUtils;
import org.eclipse.lsp4e.tests.mock.MockLanguageServer;
import org.eclipse.lsp4e.tests.mock.MockLanguageServerFactory;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRegistrationOptions;
import org.eclipse.lsp4j.DiagnosticServerCancellationData;
import org.eclipse.lsp4j.DiagnosticServerCapabilities;
import org.eclipse.lsp4j.DocumentDiagnosticParams;
import org.eclipse.lsp4j.DocumentDiagnosticReport;
import org.eclipse.lsp4j.DocumentFilter;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PreviousResultId;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport;
import org.eclipse.lsp4j.RelatedUnchangedDocumentDiagnosticReport;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PullDiagnosticsTest extends AbstractTestWithProject {

	@BeforeEach
	public void shortenDelayBetweenWorkspacePulls() {
		WorkspaceDiagnosticsPuller.setDelayBetweenPulls(100L);
	}

	@AfterEach
	public void restoreDelayBetweenWorkspacePulls() {
		WorkspaceDiagnosticsPuller.setDelayBetweenPulls(null);
	}

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
		// until after the pull that follows the opening of an editor, for the refresh to be what pulls again
		waitForCondition(3_000, () -> false);

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
		assertFalse(waitForCondition(1_000, () -> !getWorkspaceDiagnosticRequests(factory).isEmpty()));

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
		// until the request that may have been on its way arrived
		waitForCondition(1_000, () -> false);
		final int requestsAfterUnregistration = getWorkspaceDiagnosticRequests(factory).size();
		assertFalse(waitForCondition(2_000,
				() -> getWorkspaceDiagnosticRequests(factory).size() > requestsAfterUnregistration));
	}

	@Test
	public void testWorkspaceDiagnosticsGiveUpOnRepeatedErrors(MockLanguageServerFactory factory) throws Exception {
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> server.getWorkspaceService()
				.setWorkspaceDiagnostics(params -> CompletableFuture.failedFuture(new ResponseErrorException(
						new ResponseError(ResponseErrorCode.InternalError, "workspace diagnostics failed", null)))));

		TestUtils.openTextViewer(TestUtils.createUniqueTestFile(project, ""));

		// the first request and five more attempts
		waitForAndAssertCondition(20_000, () -> getWorkspaceDiagnosticRequests(factory).size() == 6);
		assertFalse(waitForCondition(2_000, () -> getWorkspaceDiagnosticRequests(factory).size() > 6));

		// a refresh is taken as a sign that the server recovered
		factory.getServer().getRemoteProxy().refreshDiagnostics().get(5, TimeUnit.SECONDS);
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).size() > 6);
	}

	@Test
	public void testWorkspaceDiagnosticsArePulledAgainHoweverOftenTheServerCancels(MockLanguageServerFactory factory)
			throws Exception {
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> server.getWorkspaceService()
				.setWorkspaceDiagnostics(params -> CompletableFuture.failedFuture(new ResponseErrorException(
						new ResponseError(ResponseErrorCode.ServerCancelled, "workspace diagnostics cancelled",
								new DiagnosticServerCancellationData(false))))));

		TestUtils.openTextViewer(TestUtils.createUniqueTestFile(project, ""));

		// more often than a failing request is sent again
		waitForAndAssertCondition(30_000, () -> getWorkspaceDiagnosticRequests(factory).size() > 7);
	}

	@Test
	public void testWorkspaceReportThatCannotBeHandledDoesNotSuppressOthers(MockLanguageServerFactory factory)
			throws Exception {
		IFile closedFile = TestUtils.createUniqueTestFile(project, "");
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> server.getWorkspaceService()
				.setWorkspaceDiagnostics(params -> CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(List.of( //
						new WorkspaceDocumentDiagnosticReport(new WorkspaceFullDocumentDiagnosticReport(
								List.of(diagnostic("nowhere to show")), "not a uri", null)),
						workspaceReport(closedFile, "from workspace pull", "result"))))));

		TestUtils.openTextViewer(TestUtils.createUniqueTestFile(project, ""));

		waitForAndAssertCondition(5_000, () -> getMarkerMessages(closedFile).equals(Set.of("from workspace pull")));
	}

	@Test
	public void testWorkspaceDiagnosticsAreShownAgainWhenFileIsDeletedAndCreatedAgain(MockLanguageServerFactory factory)
			throws Exception {
		IFile closedFile = TestUtils.createUniqueTestFile(project, "");
		final String closedUri = LSPEclipseUtils.toUri(closedFile).toString();
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		// like a server that only reports what the client does not know yet, and does not watch the files
		factory.withConfiguration((idx, server) -> server.getWorkspaceService().setWorkspaceDiagnostics(
				params -> CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(params.getPreviousResultIds()
						.stream().anyMatch(previous -> closedUri.equals(previous.getUri())) ? List.of()
								: List.of(workspaceReport(closedFile, "from workspace pull", "result"))))));

		TestUtils.openTextViewer(TestUtils.createUniqueTestFile(project, ""));
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(closedFile).equals(Set.of("from workspace pull")));
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).stream()
				.anyMatch(request -> !request.getPreviousResultIds().isEmpty()));

		// the markers go away with the file
		closedFile.delete(true, null);
		closedFile.create(new ByteArrayInputStream(new byte[0]), true, null);
		assertEquals(Set.of(), getMarkerMessages(closedFile));

		waitForAndAssertCondition(15_000, () -> getMarkerMessages(closedFile).equals(Set.of("from workspace pull")));
	}

	@Test
	public void testWorkspaceDiagnosticsOfDocumentThatIsOpenButNotPulled(MockLanguageServerFactory factory)
			throws Exception {
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> server.getWorkspaceService()
				.setWorkspaceDiagnostics(params -> CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(
						List.of(workspaceReport(testFile, "from workspace pull", "result"))))));

		// like an editor that does not pull the diagnostics of its document
		IDocument document = LSPEclipseUtils.getDocument(testFile);
		assertNotNull(document);
		final var wrappers = LanguageServers.forDocument(document)
				.collectAll((wrapper, ls) -> CompletableFuture.completedFuture(wrapper)).get(5, TimeUnit.SECONDS);
		assertEquals(1, wrappers.size());
		assertTrue(wrappers.get(0).isConnectedTo(LSPEclipseUtils.toUri(testFile)));

		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from workspace pull")));
		assertEquals(List.of(), getDiagnosticRequests(factory));
	}

	@Test
	public void testWorkspaceDiagnosticsTakeOverWhenEditorCloses(MockLanguageServerFactory factory) throws Exception {
		IFile otherFile = TestUtils.createUniqueTestFile(project, "");
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		// a server may write the URI of a document differently than the client does
		final String testUri = testFile.getLocation().toFile().toURI().toString();
		assertFalse(testUri.equals(LSPEclipseUtils.toUri(testFile).toString()));
		final var report = new WorkspaceFullDocumentDiagnosticReport(List.of(diagnostic("from workspace pull")),
				testUri, null);
		report.setResultId("result");
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> {
			server.getTextDocumentService().setPullDiagnostics(
					params -> CompletableFuture.completedFuture(List.of(diagnostic("from document pull"))));
			// like a server that only reports what the client does not know yet
			server.getWorkspaceService().setWorkspaceDiagnostics(params -> CompletableFuture
					.completedFuture(new WorkspaceDiagnosticReport(params.getPreviousResultIds().stream()
							.anyMatch(previous -> testUri.equals(previous.getUri())) ? List.of()
									: List.of(new WorkspaceDocumentDiagnosticReport(report)))));
		});

		// keeps the server running once the editor of the test file is closed
		TestUtils.openTextViewer(otherFile);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from workspace pull")));
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).stream()
				.anyMatch(request -> !request.getPreviousResultIds().isEmpty()));

		IEditorPart editor = TestUtils.openEditor(testFile);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from document pull")));
		// the document pull keeps winning while the document is open
		assertFalse(waitForCondition(1_500, () -> !getMarkerMessages(testFile).equals(Set.of("from document pull"))));

		TestUtils.closeEditor(editor, false);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from workspace pull")));
	}

	@Test
	public void testDocumentPullAnsweredAfterEditorClosedDoesNotReplaceWorkspaceDiagnostics(
			MockLanguageServerFactory factory) throws Exception {
		IFile otherFile = TestUtils.createUniqueTestFile(project, "");
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		final String testUri = LSPEclipseUtils.toUri(testFile).toString();
		final var slowAnswers = new CopyOnWriteArrayList<CompletableFuture<List<Diagnostic>>>();
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> {
			server.getTextDocumentService().setPullDiagnostics(params -> {
				if (!testUri.equals(params.getTextDocument().getUri())) {
					return CompletableFuture.completedFuture(List.of());
				}
				final var slowAnswer = new CompletableFuture<List<Diagnostic>>();
				slowAnswers.add(slowAnswer);
				return slowAnswer;
			});
			// like a server that only reports what the client does not know yet
			server.getWorkspaceService().setWorkspaceDiagnostics(params -> CompletableFuture
					.completedFuture(new WorkspaceDiagnosticReport(params.getPreviousResultIds().stream()
							.anyMatch(previous -> testUri.equals(previous.getUri())) ? List.of()
									: List.of(workspaceReport(testFile, "from workspace pull", "result")))));
		});

		// keeps the server running once the editor of the test file is closed
		TestUtils.openTextViewer(otherFile);
		IEditorPart editor = TestUtils.openEditor(testFile);
		waitForAndAssertCondition(5_000, () -> !slowAnswers.isEmpty());
		TestUtils.closeEditor(editor, false);
		final int requestsAtClose = getWorkspaceDiagnosticRequests(factory).size();
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from workspace pull")));
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).stream().skip(requestsAtClose)
				.anyMatch(request -> !request.getPreviousResultIds().isEmpty()));

		slowAnswers.forEach(slowAnswer -> slowAnswer.complete(List.of(diagnostic("from document pull"))));
		// which may be shown for a moment
		waitForCondition(1_500, () -> false);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from workspace pull")));
	}

	@Test
	public void testResultIdOfDocumentThatIsNotInTheWorkspaceIsSent(MockLanguageServerFactory factory)
			throws Exception {
		final String uri = "file:///not/in/the/workspace.lspt";
		final var report = new WorkspaceFullDocumentDiagnosticReport(List.of(diagnostic("nowhere to show")), uri, null);
		report.setResultId("result");
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> server.getWorkspaceService().setWorkspaceDiagnostics(
				params -> CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(
						List.of(new WorkspaceDocumentDiagnosticReport(report))))));

		TestUtils.openTextViewer(TestUtils.createUniqueTestFile(project, ""));

		// so that the server does not need to report it again each time
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).stream()
				.anyMatch(request -> request.getPreviousResultIds().equals(List.of(new PreviousResultId(uri, "result")))));
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
	public void testPartialWorkspaceDiagnosticResults(MockLanguageServerFactory factory) throws Exception {
		IFile openFile = TestUtils.createUniqueTestFile(project, "");
		IFile closedFile = TestUtils.createUniqueTestFile(project, "");
		final String closedUri = LSPEclipseUtils.toUri(closedFile).toString();
		final var request = new CompletableFuture<WorkspaceDiagnosticReport>();
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		// like a server that keeps the request open and reports through partial results
		final var requested = new AtomicBoolean();
		factory.withConfiguration((idx, server) -> server.getWorkspaceService().setWorkspaceDiagnostics(
				params -> requested.compareAndSet(false, true) ? request : new CompletableFuture<>()));

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
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).size() >= 2);
		assertEquals(List.of(new PreviousResultId(closedUri, "result partial 2")),
				getWorkspaceDiagnosticRequests(factory).get(1).getPreviousResultIds());
		assertEquals(Set.of("partial 2"), getMarkerMessages(closedFile));
	}

	@Test
	public void testWorkspaceRequestThatIsKeptOpenIsSentAgainWhenEditorCloses(MockLanguageServerFactory factory)
			throws Exception {
		IFile otherFile = TestUtils.createUniqueTestFile(project, "");
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> provider.setWorkspaceDiagnostics(true)));
		factory.withConfiguration((idx, server) -> {
			server.getTextDocumentService().setPullDiagnostics(
					params -> CompletableFuture.completedFuture(List.of(diagnostic("from document pull"))));
			// like a server that never completes a request and reports through partial results
			server.getWorkspaceService().setWorkspaceDiagnostics(params -> new CompletableFuture<>());
		});

		// keeps the server running once the editor of the test file is closed
		TestUtils.openTextViewer(otherFile);
		waitForAndAssertCondition(5_000, () -> !getWorkspaceDiagnosticRequests(factory).isEmpty());
		final var client = factory.getServer().getRemoteProxy();
		client.notifyProgress(new ProgressParams(getWorkspaceDiagnosticRequests(factory).get(0).getPartialResultToken(),
				Either.forRight(new WorkspaceDiagnosticReportPartialResult(
						List.of(workspaceReport(testFile, "from workspace pull", "result"))))));
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from workspace pull")));

		IEditorPart editor = TestUtils.openEditor(testFile);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from document pull")));
		assertEquals(1, getWorkspaceDiagnosticRequests(factory).size());

		// the server would not report the document again in answer to the request that it keeps open
		TestUtils.closeEditor(editor, false);
		waitForAndAssertCondition(5_000, () -> getWorkspaceDiagnosticRequests(factory).size() >= 2);
		final var again = getWorkspaceDiagnosticRequests(factory).get(1);
		assertEquals(List.of(), again.getPreviousResultIds());
		client.notifyProgress(new ProgressParams(again.getPartialResultToken(),
				Either.forRight(new WorkspaceDiagnosticReportPartialResult(
						List.of(workspaceReport(testFile, "from workspace pull again", "result 2"))))));
		waitForAndAssertCondition(5_000,
				() -> getMarkerMessages(testFile).equals(Set.of("from workspace pull again")));
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

	@Test
	public void testPreviousResultIdIsSentAndUnchangedReportKeepsDiagnostics(MockLanguageServerFactory factory)
			throws Exception {
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> {}));
		factory.withConfiguration((idx, server) -> server.getTextDocumentService().setPullDiagnosticReports(params -> {
			if (params.getPreviousResultId() == null) {
				final var full = new RelatedFullDocumentDiagnosticReport(List.of(diagnostic("from full report")));
				full.setResultId("result 1");
				return CompletableFuture.completedFuture(new DocumentDiagnosticReport(full));
			}
			return CompletableFuture.completedFuture(
					new DocumentDiagnosticReport(new RelatedUnchangedDocumentDiagnosticReport("result 2")));
		}));

		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from full report")));
		assertEquals(null, getDiagnosticRequests(factory).get(0).getPreviousResultId());

		viewer.getDocument().replace(0, 0, "a");
		waitForAndAssertCondition(5_000, () -> getDiagnosticRequests(factory).stream()
				.anyMatch(request -> "result 1".equals(request.getPreviousResultId())));
		// the result id of an unchanged report replaces the one of the full report
		viewer.getDocument().replace(0, 0, "b");
		waitForAndAssertCondition(5_000, () -> getDiagnosticRequests(factory).stream()
				.anyMatch(request -> "result 2".equals(request.getPreviousResultId())));
		assertEquals(Set.of("from full report"), getMarkerMessages(testFile));
	}

	@Test
	public void testPullThatIsOvertakenByLaterPullIsCancelled(MockLanguageServerFactory factory) throws Exception {
		final var slowAnswers = new CopyOnWriteArrayList<CompletableFuture<DocumentDiagnosticReport>>();
		final var answer = new AtomicReference<Supplier<CompletableFuture<DocumentDiagnosticReport>>>(
				() -> CompletableFuture.completedFuture(fullReport("first", "result 1")));
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> {}));
		factory.withConfiguration(
				(idx, server) -> server.getTextDocumentService().setPullDiagnosticReports(params -> answer.get().get()));

		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("first")));

		answer.set(() -> {
			final var slowAnswer = new CompletableFuture<DocumentDiagnosticReport>();
			slowAnswers.add(slowAnswer);
			return slowAnswer;
		});
		viewer.getDocument().replace(0, 0, "a");
		waitForAndAssertCondition(5_000, () -> !slowAnswers.isEmpty());
		answer.set(() -> CompletableFuture.completedFuture(fullReport("third", "result 3")));
		viewer.getDocument().replace(0, 0, "b");
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("third")));

		// an answer to it would be about the first report, not the third
		waitForAndAssertCondition(5_000, () -> slowAnswers.stream().allMatch(CompletableFuture::isCancelled));
		viewer.getDocument().replace(0, 0, "c");
		waitForAndAssertCondition(5_000, () -> "result 3".equals(
				getDiagnosticRequests(factory).get(getDiagnosticRequests(factory).size() - 1).getPreviousResultId()));
		assertEquals(Set.of("third"), getMarkerMessages(testFile));
	}

	@Test
	public void testDiagnosticsOfUnregisteredIdentifierAreRemoved(MockLanguageServerFactory factory) throws Exception {
		factory.withConfiguration((idx, server) -> server.getTextDocumentService().setPullDiagnosticReports(params -> {
			if (params.getPreviousResultId() != null) {
				return CompletableFuture.completedFuture(new DocumentDiagnosticReport(
						new RelatedUnchangedDocumentDiagnosticReport(params.getPreviousResultId())));
			}
			return CompletableFuture
					.completedFuture(fullReport("from " + params.getIdentifier(), "result " + params.getIdentifier()));
		}));

		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		registerSyntaxAndSemanticDiagnostics(factory, testFile);
		viewer.getDocument().replace(0, 0, "a");
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from syntax", "from semantic")));

		factory.getServer().getRemoteProxy().unregisterCapability(new UnregistrationParams(
				List.of(new Unregistration("semantic", LSPMethods.TEXT_DOCUMENT_DIAGNOSTIC)))).get(1, TimeUnit.SECONDS);
		// without an edit, and although the answer for the identifier that is left is that nothing changed
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("from syntax")));

		factory.getServer().getRemoteProxy().unregisterCapability(new UnregistrationParams(
				List.of(new Unregistration("syntax", LSPMethods.TEXT_DOCUMENT_DIAGNOSTIC)))).get(1, TimeUnit.SECONDS);
		// although there is nothing left to pull from the server
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of()));
	}

	private static DocumentDiagnosticReport fullReport(String message, String resultId) {
		final var full = new RelatedFullDocumentDiagnosticReport(List.of(diagnostic(message)));
		full.setResultId(resultId);
		return new DocumentDiagnosticReport(full);
	}

	@Test
	public void testDocumentIsPulledAgainAfterServerCancelled(MockLanguageServerFactory factory) throws Exception {
		final var cancellations = new AtomicReference<Boolean>(Boolean.TRUE);
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> {}));
		factory.withConfiguration((idx, server) -> server.getTextDocumentService().setPullDiagnostics(params -> {
			final Boolean retrigger = cancellations.get();
			return retrigger == null ? CompletableFuture.completedFuture(List.of(diagnostic("ready")))
					: CompletableFuture.failedFuture(serverCancelled(retrigger));
		}));

		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		TestUtils.openTextViewer(testFile);
		// asked again and again while the server cancels, without anything happening in the editor
		waitForAndAssertCondition(10_000, () -> getDiagnosticRequests(factory).size() > 3);
		cancellations.set(null);
		waitForAndAssertCondition(10_000, () -> getMarkerMessages(testFile).equals(Set.of("ready")));
	}

	@Test
	public void testDocumentIsNotPulledAgainWhenServerDeclinesRetrigger(MockLanguageServerFactory factory)
			throws Exception {
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> {}));
		factory.withConfiguration((idx, server) -> server.getTextDocumentService()
				.setPullDiagnostics(params -> CompletableFuture.failedFuture(serverCancelled(false))));

		TestUtils.openTextViewer(TestUtils.createUniqueTestFile(project, ""));
		waitForAndAssertCondition(5_000, () -> !getDiagnosticRequests(factory).isEmpty());
		// the pulls on opening the editor, and none after them
		waitForCondition(3_000, () -> false);
		final int requests = getDiagnosticRequests(factory).size();
		assertFalse(waitForCondition(4_000, () -> getDiagnosticRequests(factory).size() > requests));
	}

	@Test
	public void testServerThatCancelledIsPulledAgainWhileOtherServerIsPulled(MockLanguageServerFactory factory)
			throws Exception {
		// the servers are not always listed in the order they are configured in
		final var cancellingServer = new AtomicReference<MockLanguageServer>();
		final var otherServer = new AtomicReference<MockLanguageServer>();
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> {}));
		factory.withConfiguration((idx, server) -> {
			(idx == 0 ? cancellingServer : otherServer).set(server);
			server.getTextDocumentService()
					.setPullDiagnostics(params -> idx == 0 ? CompletableFuture.failedFuture(serverCancelled(true))
							: CompletableFuture.completedFuture(List.of()));
		});

		TestUtils.openTextViewer(TestUtils.createUniqueTestFileMultiLS(project, ""));
		waitForAndAssertCondition(5_000, () -> cancellingServer.get() != null && otherServer.get() != null);
		final var cancelling = cancellingServer.get().getTextDocumentService();
		waitForAndAssertCondition(5_000, () -> !cancelling.getDiagnosticRequests().isEmpty());
		// until after the pull that follows the opening of the editor, which is for both servers
		waitForCondition(3_000, () -> false);

		waitForAndAssertCondition(5_000, () -> otherServer.get().getRemoteProxy() != null);
		otherServer.get().getRemoteProxy().refreshDiagnostics().get(5, TimeUnit.SECONDS);
		final int requests = cancelling.getDiagnosticRequests().size();
		// the one that may have been on its way, and one more
		waitForAndAssertCondition(20_000, () -> cancelling.getDiagnosticRequests().size() >= requests + 2);
	}

	private static ResponseErrorException serverCancelled(boolean retrigger) {
		return new ResponseErrorException(new ResponseError(ResponseErrorCode.ServerCancelled, "not ready",
				new DiagnosticServerCancellationData(retrigger)));
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
