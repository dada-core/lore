package com.dadacore.lore.ingestion.worker;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;

/**
 * The places of a Markdown document: the part before the first title, then one per title, at any
 * depth. The rendering numbers its titles from the same visit, so that {@code h-7} is the same
 * title in the chunks and in the rendering.
 */
final class MarkdownPlaces extends AbstractVisitor {

    final List<XhtmlText.Place> places = new ArrayList<>();
    /** The number of each title that counts, by node: empty titles have none. */
    final Map<Heading, Integer> numbers = new IdentityHashMap<>();
    final Map<Heading, String> titles = new IdentityHashMap<>();
    private final List<String> path = new ArrayList<>();
    private XhtmlText.Place current = new XhtmlText.Place(0, List.of(), new ArrayList<>());
    String firstTitle;
    private int count;

    MarkdownPlaces() {
        places.add(current);
    }

    @Override
    public void visit(Heading heading) {
        String title = XhtmlText.heading(text(heading));
        if (title.isEmpty()) {
            return;
        }
        if (heading.getLevel() == 1 && firstTitle == null) {
            firstTitle = title;
        }
        int level = Math.min(heading.getLevel(), path.size() + 1);
        while (path.size() >= level) {
            path.removeLast();
        }
        path.add(title);
        count++;
        numbers.put(heading, count);
        titles.put(heading, title);
        current = new XhtmlText.Place(count, List.copyOf(path), new ArrayList<>());
        places.add(current);
    }

    @Override
    public void visit(Paragraph paragraph) {
        current.paragraphs().add(text(paragraph));
    }

    @Override
    public void visit(FencedCodeBlock code) {
        current.paragraphs().add(code.getLiteral());
    }

    @Override
    public void visit(IndentedCodeBlock code) {
        current.paragraphs().add(code.getLiteral());
    }

    @Override
    public void visit(ListItem item) {
        // Each item is a paragraph of its own, read through its children.
        visitChildren(item);
    }

    @Override
    public void visit(BlockQuote quote) {
        visitChildren(quote);
    }

    @Override
    public void visit(CustomBlock block) {
        if (block instanceof TableBlock table) {
            // A table is searched row by row, its cells apart.
            for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
                for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                    if (row instanceof TableRow) {
                        List<String> cells = new ArrayList<>();
                        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                            if (cell instanceof TableCell) {
                                cells.add(text(cell));
                            }
                        }
                        current.paragraphs().add(String.join(" | ", cells));
                    }
                }
            }
            return;
        }
        visitChildren(block);
    }

    /** The text of a node, its line breaks kept. */
    static String text(Node node) {
        StringBuilder text = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text literal) {
                text.append(literal.getLiteral());
            }

            @Override
            public void visit(Code code) {
                text.append(code.getLiteral());
            }

            @Override
            public void visit(SoftLineBreak lineBreak) {
                text.append(' ');
            }

            @Override
            public void visit(HardLineBreak lineBreak) {
                text.append('\n');
            }
        });
        return text.toString();
    }
}
