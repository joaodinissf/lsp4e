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

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineContentCodeMining;
import org.eclipse.jface.text.codemining.LineHeaderCodeMining;
import org.eclipse.lsp4e.operations.inlinecompletion.GhostTextLayout.MiningSpec;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

/**
 * The code minings that draw ghost text. They draw with the editor font, so that the ghost text is as
 * wide as the text it previews, only into the editor showing the inline completion (the annotation
 * model may be shared with another editor on the same document), and their labels can be updated in
 * place while the user types through the ghost text.
 */
final class GhostTextMinings {

	interface GhostMining extends ICodeMining {
		/** Updates what the mining draws to the given specification of the same kind and position. */
		void update(MiningSpec spec);
	}

	private GhostTextMinings() {
	}

	static GhostMining create(MiningSpec spec, Position position, ICodeMiningProvider provider,
			InlineCompletionController controller) throws BadLocationException {
		return switch (spec.kind()) {
		case CONTENT -> new Content(position, provider, controller, spec);
		case HEADER -> new Header(position, provider, controller, spec);
		case END_OF_DOCUMENT -> new EndOfDocument(position, provider, controller, spec);
		};
	}

	/** Draws the first line of the ghost text at the caret. */
	static final class Content extends LineContentCodeMining implements GhostMining {
		private final InlineCompletionController controller;

		Content(Position position, ICodeMiningProvider provider, InlineCompletionController controller, MiningSpec spec) {
			super(position, true, provider);
			this.controller = controller;
			update(spec);
		}

		@Override
		public void update(MiningSpec spec) {
			setLabel(spec.firstRow());
		}

		@Override
		public Point draw(GC gc, StyledText textWidget, Color color, int x, int y) {
			if (!controller.isOwnWidget(textWidget)) {
				return new Point(0, 0);
			}
			gc.setFont(textWidget.getFont());
			final String text = getLabel();
			final String label = fitToWidth(gc, textWidget, text == null ? "" : text, x); //$NON-NLS-1$
			gc.drawString(label, x, y, true);
			controller.ghostPainted();
			final Point extent = gc.stringExtent(label);
			extent.y += textWidget.getLineSpacing();
			return extent;
		}
	}

	/** Draws the further lines of the ghost text above the next visible line. */
	static final class Header extends LineHeaderCodeMining implements GhostMining {
		private final InlineCompletionController controller;

		Header(Position position, ICodeMiningProvider provider, InlineCompletionController controller, MiningSpec spec)
				throws BadLocationException {
			super(position, provider, null);
			this.controller = controller;
			update(spec);
		}

		@Override
		public void update(MiningSpec spec) {
			setLabel(String.join("\n", spec.rows())); //$NON-NLS-1$
		}

		@Override
		public Point draw(GC gc, StyledText textWidget, Color color, int x, int y) {
			if (!controller.isOwnWidget(textWidget)) {
				return new Point(0, 0);
			}
			gc.setFont(textWidget.getFont());
			controller.ghostPainted();
			return super.draw(gc, textWidget, color, x, y);
		}
	}

	/**
	 * Draws ghost text at the end of the document: its first line at the end of the last line, its
	 * further lines below. JFace reserves no room below the last line, so the controller enlarges the
	 * editor's bottom margin while such ghost text is shown.
	 */
	static final class EndOfDocument extends LineContentCodeMining implements GhostMining {
		private final InlineCompletionController controller;
		private volatile String firstRow = ""; //$NON-NLS-1$
		private volatile List<String> rows = List.of();
		private volatile @Nullable StyledText widget;

		EndOfDocument(Position position, ICodeMiningProvider provider, InlineCompletionController controller, MiningSpec spec) {
			super(position, true, provider);
			this.controller = controller;
			update(spec);
		}

		@Override
		public void update(MiningSpec spec) {
			firstRow = spec.firstRow();
			rows = spec.rows();
			// the label must not be empty for the mining to be drawn
			setLabel(firstRow.isEmpty() ? " " : firstRow); //$NON-NLS-1$
		}

		@Override
		public Point draw(GC gc, StyledText textWidget, Color color, int x, int y) {
			if (!controller.isOwnWidget(textWidget)) {
				return new Point(0, 0);
			}
			gc.setFont(textWidget.getFont());
			final String first = fitToWidth(gc, textWidget, firstRow, x);
			gc.drawString(first, x, y, true);
			final int lineHeight = textWidget.getLineHeight() + textWidget.getLineSpacing();
			final int left = textWidget.getLeftMargin() - textWidget.getHorizontalPixel();
			int rowY = y + lineHeight;
			for (final String row : rows) {
				gc.drawString(row, left, rowY, true);
				rowY += lineHeight;
			}
			widget = textWidget;
			// Only the last line's area is repainted when the mining is drawn, which clips the lines
			// below it: repaint from the last line down to the end of the ghost text.
			final Rectangle clipping = gc.getClipping();
			if (!rows.isEmpty() && clipping.y + clipping.height < rowY) {
				final int top = y;
				final int height = rowY - y;
				textWidget.getDisplay().asyncExec(() -> {
					if (!textWidget.isDisposed()) {
						textWidget.redraw(0, top, textWidget.getClientArea().width, height, false);
					}
				});
			}
			controller.ghostPainted();
			return new Point(gc.stringExtent(first).x, lineHeight);
		}

		@Override
		public void dispose() {
			super.dispose();
			// what was drawn below the last line is outside any line, so nothing else repaints it
			final StyledText drawnOn = widget;
			if (drawnOn != null && !drawnOn.isDisposed()) {
				drawnOn.getDisplay().asyncExec(() -> {
					if (!drawnOn.isDisposed()) {
						drawnOn.redraw();
					}
				});
			}
		}
	}

	/**
	 * With word wrap on, the widened character the first line is drawn on cannot wrap, so the line is
	 * cut to the space left on the visual line.
	 */
	static String fitToWidth(GC gc, StyledText textWidget, String label, int x) {
		if (!textWidget.getWordWrap()) {
			return label;
		}
		final int available = textWidget.getClientArea().width - textWidget.getRightMargin() - x;
		if (gc.stringExtent(label).x <= available) {
			return label;
		}
		int length = label.length();
		while (length > 0 && gc.stringExtent(label.substring(0, length) + '…').x > available) {
			length--;
		}
		return label.substring(0, length) + '…';
	}
}
