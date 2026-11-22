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
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentServerCapabilities;
import org.eclipse.lsp4j.WorkspaceDiagnosticParams;
import org.eclipse.lsp4j.WorkspaceDiagnosticReport;
import org.eclipse.lsp4j.WorkspaceDocumentDiagnosticReport;
import org.eclipse.lsp4j.WorkspaceFullDocumentDiagnosticReport;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
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
		// until after the pull that follows the opening of an editor, for the refresh to be what pulls again
		waitForCondition(3_000, () -> false);

		message.set("after refresh");
		factory.getServer().getRemoteProxy().refreshDiagnostics().get(5, TimeUnit.SECONDS);

		waitForAndAssertCondition(5_000, () -> getMarkerMessages(testFile).equals(Set.of("after refresh")));
		// the server did not announce workspace diagnostics, so it must not be asked for them
		assertEquals(List.of(), factory.getServer().getWorkspaceService().getDiagnosticRequests());
	}

	@Test
	public void testRefreshPullsWorkspaceDiagnosticsWhenSupported(MockLanguageServerFactory factory) throws Exception {
		IFile openFile = TestUtils.createUniqueTestFile(project, "");
		IFile closedFile = TestUtils.createUniqueTestFile(project, "");
		factory.withCapabilities(() -> withDiagnosticProvider(provider -> {
			provider.setIdentifier("test-identifier");
			provider.setWorkspaceDiagnostics(true);
		}));
		factory.withConfiguration((idx, server) -> {
			server.getTextDocumentService().setPullDiagnostics(
					params -> CompletableFuture.completedFuture(List.of(diagnostic("from document pull"))));
			server.getWorkspaceService().setWorkspaceDiagnostics(
					params -> CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(List.of( //
							workspaceReport(openFile, "from workspace pull"),
							workspaceReport(closedFile, "from workspace pull")))));
		});

		TestUtils.openTextViewer(openFile);
		waitForAndAssertCondition(5_000, () -> getMarkerMessages(openFile).equals(Set.of("from document pull")));

		factory.getServer().getRemoteProxy().refreshDiagnostics().get(5, TimeUnit.SECONDS);

		waitForAndAssertCondition(5_000, () -> getMarkerMessages(closedFile).equals(Set.of("from workspace pull")));
		// the document pull wins for a document that is open
		assertEquals(Set.of("from document pull"), getMarkerMessages(openFile));
		WorkspaceDiagnosticParams request = factory.getServer().getWorkspaceService().getDiagnosticRequests().get(0);
		assertEquals("test-identifier", request.getIdentifier());
		assertEquals(List.of(), request.getPreviousResultIds());
	}

	private static WorkspaceDocumentDiagnosticReport workspaceReport(IFile file, String message) {
		return new WorkspaceDocumentDiagnosticReport(new WorkspaceFullDocumentDiagnosticReport(
				List.of(diagnostic(message)), LSPEclipseUtils.toUri(file).toString(), null));
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
