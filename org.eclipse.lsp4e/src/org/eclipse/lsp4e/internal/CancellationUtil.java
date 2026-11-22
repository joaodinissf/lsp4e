/*******************************************************************************
 * Copyright (c) 2023 Avaloq Group AG.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Rubén Porras Campo (Avaloq Group AG) - Initial Implementation
 *******************************************************************************/
package org.eclipse.lsp4e.internal;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;

import com.google.gson.JsonElement;

public final class CancellationUtil {

	private CancellationUtil() {
		// this class shouldn't be instantiated
	}

	public static boolean isRequestCancelledException(@Nullable Throwable throwable) {
		if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
			throwable = throwable.getCause();
		}
		if (throwable instanceof ResponseErrorException responseErrorException) {
			return isRequestCancelled(responseErrorException);
		}
		return throwable instanceof CancellationException;
	}

	/**
	 * @return whether the server cancelled the request, as opposed to the client or a failure
	 */
	public static boolean isServerCancelledException(@Nullable Throwable throwable) {
		final ResponseError responseError = getResponseError(throwable);
		return responseError != null && responseError.getCode() == ResponseErrorCode.ServerCancelled.getValue();
	}

	/**
	 * @return whether the server cancelled the request and asked not to send it again, with the
	 *         {@code retriggerRequest} of a {@code DiagnosticServerCancellationData}
	 */
	public static boolean isRetriggerDeclinedException(@Nullable Throwable throwable) {
		if (!isServerCancelledException(throwable)) {
			return false;
		}
		final ResponseError responseError = getResponseError(throwable);
		final Object data = responseError == null ? null : responseError.getData();
		if (data == null) {
			return false;
		}
		try {
			// the data is a JSON element when it was read from the server, anything else is turned into one
			final JsonElement json = JsonUtil.LSP4J_GSON.toJsonTree(data);
			final JsonElement retrigger = json.isJsonObject() ? json.getAsJsonObject().get("retriggerRequest") //$NON-NLS-1$
					: null;
			return retrigger != null && retrigger.isJsonPrimitive() && retrigger.getAsJsonPrimitive().isBoolean()
					&& !retrigger.getAsBoolean();
		} catch (RuntimeException ex) {
			return false;
		}
	}

	private static @Nullable ResponseError getResponseError(@Nullable Throwable throwable) {
		if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
			throwable = throwable.getCause();
		}
		return throwable instanceof ResponseErrorException responseErrorException
				? responseErrorException.getResponseError()
				: null;
	}

	private static boolean isRequestCancelled(ResponseErrorException responseErrorException) {
		ResponseError responseError = responseErrorException.getResponseError();
		return responseError != null
				&& (responseError.getCode() == ResponseErrorCode.RequestCancelled.getValue()
						|| responseError.getCode() == ResponseErrorCode.ServerCancelled.getValue());
	}

}
