/*
 * {{{ header & license
 * Copyright (c) 2004, 2005 Torbjoern Gannholm, Joshua Marinacci
 * Copyright (c) 2006 Wisconsin Court System
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 * }}}
 */
package com.openhtmltopdf.layout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

import com.openhtmltopdf.bidi.BidiSplitter;
import com.openhtmltopdf.bidi.BidiTextRun;
import com.openhtmltopdf.bidi.ParagraphSplitter.Paragraph;
import com.openhtmltopdf.css.constants.CSSName;
import com.openhtmltopdf.css.constants.IdentValue;
import com.openhtmltopdf.css.constants.MarginBoxName;
import com.openhtmltopdf.css.constants.PageElementPosition;
import com.openhtmltopdf.css.extend.ContentFunction;
import com.openhtmltopdf.css.newmatch.CascadedStyle;
import com.openhtmltopdf.css.newmatch.PageInfo;
import com.openhtmltopdf.css.parser.CSSPrimitiveValue;
import com.openhtmltopdf.css.parser.FSFunction;
import com.openhtmltopdf.css.parser.PropertyValue;
import com.openhtmltopdf.css.sheet.PropertyDeclaration;
import com.openhtmltopdf.css.sheet.StylesheetInfo;
import com.openhtmltopdf.css.style.CalculatedStyle;
import com.openhtmltopdf.css.style.EmptyStyle;
import com.openhtmltopdf.css.style.FSDerivedValue;
import com.openhtmltopdf.layout.counter.AbstractCounterContext;
import com.openhtmltopdf.layout.counter.RootCounterContext;
import com.openhtmltopdf.newtable.TableBox;
import com.openhtmltopdf.newtable.TableCellBox;
import com.openhtmltopdf.newtable.TableColumn;
import com.openhtmltopdf.newtable.TableRowBox;
import com.openhtmltopdf.newtable.TableSectionBox;
import com.openhtmltopdf.render.AnonymousBlockBox;
import com.openhtmltopdf.render.BlockBox;
import com.openhtmltopdf.render.Box;
import com.openhtmltopdf.render.FloatedBoxData;
import com.openhtmltopdf.render.FlowingColumnBox;
import com.openhtmltopdf.render.FlowingColumnContainerBox;
import com.openhtmltopdf.render.InlineBox;
import com.openhtmltopdf.util.OpenUtil;

/**
 * This class is responsible for creating the box tree from the DOM.  This is
 * mostly just a one-to-one translation from the <code>Element</code> to an
 * <code>InlineBox</code> or a <code>BlockBox</code> (or some subclass of
 * <code>BlockBox</code>), but the tree is reorganized according to the CSS rules.
 * This includes inserting anonymous block and inline boxes, anonymous table
 * content, and <code>:before</code> and <code>:after</code> content.  White
 * space is also normalized at this point.  Table columns and table column groups
 * are added to the table which owns them, but are not created as regular boxes.
 * Floated and absolutely positioned content is always treated as inline
 * content for purposes of inserting anonymous block boxes and calculating
 * the kind of content contained in a given block box.
 */
public class BoxBuilder {
    public static final int MARGIN_BOX_VERTICAL = 1;
    public static final int MARGIN_BOX_HORIZONTAL = 2;

    private static final int CONTENT_LIST_DOCUMENT = 1;
    private static final int CONTENT_LIST_MARGIN_BOX = 2;

    /**
     * Split the document into paragraphs for use in analyzing bi-directional text runs.
     * @param c
     * @param document
     */
    private static void splitParagraphs(LayoutContext c, Document document) {
        c.getParagraphSplitter().splitRoot(c, document);
        c.getParagraphSplitter().runBidiOnParagraphs(c);
    }

    public static BlockBox createRootBox(LayoutContext c, Document document) {
        splitParagraphs(c, document);

        Element root = document.getDocumentElement();

        CalculatedStyle style = c.getSharedContext().getStyle(root);

        BlockBox result;
        if (style.isTable() || style.isInlineTable()) {
            result = new TableBox();
        } else {
            result = new BlockBox();
        }

        result.setStyle(style);
        result.setElement(root);

        c.resolveCounters(style);

        return result;
    }

    public static void createChildren(LayoutContext c, BlockBox parent) {
        if (parent.shouldBeReplaced()) {
            // Don't create boxes for elements in a SVG element.
            // This avoids many warnings and improves performance.
            parent.setChildrenContentType(BlockBox.CONTENT_EMPTY);
            return;
        }

        List<Styleable> children = new ArrayList<>();

        ChildBoxInfo info = new ChildBoxInfo();

        createChildren(c, parent, parent.getElement(), children, info, false);

        boolean parentIsNestingTableContent = isNestingTableContent(parent.getStyle().getIdent(
                CSSName.DISPLAY));

        if (!parentIsNestingTableContent && !info.isContainsTableContent()) {
            resolveChildren(c, parent, children, info);
        } else {
            stripAllWhitespace(children);
            if (parentIsNestingTableContent) {
                resolveTableContent(c, parent, children, info);
            } else {
                resolveChildTableContent(c, parent, children, info, IdentValue.TABLE_CELL);
            }
        }
    }

    public static TableBox createMarginTable(
            LayoutContext c,
            PageInfo pageInfo,
            MarginBoxName[] names,
            int height,
            int direction)
    {
        if (! pageInfo.hasAny(names)) {
            return null;
        }

        Element source = c.getRootLayer().getMaster().getElement(); // HACK

        ChildBoxInfo info = new ChildBoxInfo();
        CalculatedStyle pageStyle = new EmptyStyle().deriveStyle(pageInfo.getPageStyle());

        CalculatedStyle tableStyle = pageStyle.deriveStyle(
                CascadedStyle.createLayoutStyle(new PropertyDeclaration[] {
                        new PropertyDeclaration(
                                CSSName.DISPLAY,
                                new PropertyValue(IdentValue.TABLE),
                                true,
                                StylesheetInfo.USER),
                        new PropertyDeclaration(
                                CSSName.WIDTH,
                                new PropertyValue(CSSPrimitiveValue.CSS_PERCENTAGE, 100.0f, "100%"),
                                true,
                                StylesheetInfo.USER),
                }));
        TableBox result = (TableBox)createBlockBox(tableStyle, info, false);
        result.setMarginAreaRoot(true);
        result.setStyle(tableStyle);
        result.setElement(source);
        result.setAnonymous(true);
        result.setChildrenContentType(BlockBox.CONTENT_BLOCK);

        CalculatedStyle tableSectionStyle = pageStyle.createAnonymousStyle(IdentValue.TABLE_ROW_GROUP);
        TableSectionBox section = (TableSectionBox)createBlockBox(tableSectionStyle, info, false);
        section.setStyle(tableSectionStyle);
        section.setElement(source);
        section.setAnonymous(true);
        section.setChildrenContentType(BlockBox.CONTENT_BLOCK);

        result.addChild(section);

        TableRowBox row = null;
        if (direction == MARGIN_BOX_HORIZONTAL) {
            CalculatedStyle tableRowStyle = pageStyle.createAnonymousStyle(IdentValue.TABLE_ROW);
            row = (TableRowBox)createBlockBox(tableRowStyle, info, false);
            row.setStyle(tableRowStyle);
            row.setElement(source);
            row.setAnonymous(true);
            row.setChildrenContentType(BlockBox.CONTENT_BLOCK);

            row.setHeightOverride(height);

            section.addChild(row);
        }

        int cellCount = 0;
        boolean alwaysCreate = names.length > 1 && direction == MARGIN_BOX_HORIZONTAL;

        for (int i = 0; i < names.length; i++) {
            CascadedStyle cellStyle = pageInfo.createMarginBoxStyle(names[i], alwaysCreate);
            if (cellStyle != null) {
                TableCellBox cell = createMarginBox(c, cellStyle, alwaysCreate);
                if (cell != null) {
                    if (direction == MARGIN_BOX_VERTICAL) {
                        CalculatedStyle tableRowStyle = pageStyle.createAnonymousStyle(IdentValue.TABLE_ROW);
                        row = (TableRowBox)createBlockBox(tableRowStyle, info, false);
                        row.setStyle(tableRowStyle);
                        row.setElement(source);
                        row.setAnonymous(true);
                        row.setChildrenContentType(BlockBox.CONTENT_BLOCK);

                        row.setHeightOverride(height);

                        section.addChild(row);
                    }
                    row.addChild(cell);
                    cellCount++;
                }
            }
        }

        if (direction == MARGIN_BOX_VERTICAL && cellCount > 0) {
            int rHeight = 0;
            for (Iterator<Box> i = section.getChildIterator(); i.hasNext(); ) {
                TableRowBox r = (TableRowBox)i.next();
                r.setHeightOverride(height / cellCount);
                rHeight += r.getHeightOverride();
            }

            for (Iterator<Box> i = section.getChildIterator(); i.hasNext() && rHeight < height; ) {
                TableRowBox r = (TableRowBox)i.next();
                r.setHeightOverride(r.getHeightOverride()+1);
                rHeight++;
            }
        }

        return cellCount > 0 ? result : null;
    }

    private static TableCellBox createMarginBox(
            LayoutContext c,
            CascadedStyle cascadedStyle,
            boolean alwaysCreate) {
        boolean hasContent = true;

        PropertyDeclaration contentDecl = cascadedStyle.propertyByName(CSSName.CONTENT);

        CalculatedStyle style = new EmptyStyle().deriveStyle(cascadedStyle);

        if (style.isDisplayNone() && ! alwaysCreate) {
            return null;
        }

        if (style.isIdent(CSSName.CONTENT, IdentValue.NONE) ||
                style.isIdent(CSSName.CONTENT, IdentValue.NORMAL)) {
            hasContent = false;
        }

        if (style.isAutoWidth() && ! alwaysCreate && ! hasContent) {
            return null;
        }

        List<Styleable> children = new ArrayList<>();

        ChildBoxInfo info = new ChildBoxInfo();
        info.setContainsTableContent(true);
        info.setLayoutRunningBlocks(true);

        TableCellBox result = new TableCellBox();
        result.setAnonymous(true);
        result.setStyle(style);
        result.setElement(c.getRootLayer().getMaster().getElement()); // XXX Doesn't make sense, but we need something here

        if (hasContent && ! style.isDisplayNone()) {
            children.addAll(createGeneratedMarginBoxContent(
                    c,
                    c.getRootLayer().getMaster().getElement(),
                    (PropertyValue)contentDecl.getValue(),
                    style,
                    info));

            stripAllWhitespace(children);
        }

        resolveChildTableContent(c, result, children, info, IdentValue.TABLE_CELL);

        return result;
    }

    private static void resolveChildren(
            LayoutContext c, BlockBox owner, List<Styleable> children, ChildBoxInfo info) {
        if (children.size() > 0) {
            if (info.isContainsBlockLevelContent()) {
                insertAnonymousBlocks(
                        c.getSharedContext(), owner, children, info.isLayoutRunningBlocks());
                owner.setChildrenContentType(BlockBox.CONTENT_BLOCK);
            } else {
                WhitespaceStripper.stripInlineContent(children);
                if (children.size() > 0) {
                    owner.setInlineContent(children);
                    owner.setChildrenContentType(BlockBox.CONTENT_INLINE);
                } else {
                    owner.setChildrenContentType(BlockBox.CONTENT_EMPTY);
                }
            }
        } else {
            owner.setChildrenContentType(BlockBox.CONTENT_EMPTY);
        }
    }

    private static boolean isAllProperTableNesting(IdentValue parentDisplay, List<Styleable> children) {
        return children.stream().allMatch(child -> isProperTableNesting(parentDisplay, child.getStyle().getIdent(CSSName.DISPLAY)));
    }

    /**
     * Handles the situation when we find table content, but our parent is not
     * table related.  For example, <code>div</code> -> <code>td</td></code>.
     * Anonymous tables are then constructed by repeatedly pulling together
     * consecutive same-table-level siblings and wrapping them in the next
     * highest table level (e.g. consecutive <code>td</code> elements will
     * be wrapped in an anonymous <code>tr</code>, then a <code>tbody</code>, and
     * finally a <code>table</code>).
     */
    private static void resolveChildTableContent(
            LayoutContext c, BlockBox parent, List<Styleable> children, ChildBoxInfo info, IdentValue target) {
        List<Styleable> childrenForAnonymous = new ArrayList<>();
        List<Styleable> childrenWithAnonymous = new ArrayList<>();

        IdentValue nextUp = getPreviousTableNestingLevel(target);
        
        for (Styleable styleable : children) {
            if (matchesTableLevel(target, styleable.getStyle().getIdent(CSSName.DISPLAY))) {
                childrenForAnonymous.add(styleable);
            } else {
                if (childrenForAnonymous.size() > 0) {
                    createAnonymousTableContent(c, (BlockBox) childrenForAnonymous.get(0), nextUp,
                            childrenForAnonymous, childrenWithAnonymous);

                    childrenForAnonymous = new ArrayList<>();
                }
                childrenWithAnonymous.add(styleable);
            }
        }

        if (childrenForAnonymous.size() > 0) {
            createAnonymousTableContent(c, (BlockBox) childrenForAnonymous.get(0), nextUp,
                    childrenForAnonymous, childrenWithAnonymous);
        }

        if (nextUp == IdentValue.TABLE) {
            rebalanceInlineContent(childrenWithAnonymous);
            info.setContainsBlockLevelContent(true);
            resolveChildren(c, parent, childrenWithAnonymous, info);
        } else {
            resolveChildTableContent(c, parent, childrenWithAnonymous, info, nextUp);
        }
    }

    private static boolean matchesTableLevel(IdentValue target, IdentValue value) {
        if (target == IdentValue.TABLE_ROW_GROUP) {
            return value == IdentValue.TABLE_ROW_GROUP || value == IdentValue.TABLE_HEADER_GROUP
                    || value == IdentValue.TABLE_FOOTER_GROUP || value == IdentValue.TABLE_CAPTION;
        } else {
            return target == value;
        }
    }

    /**
     * Makes sure that any <code>InlineBox</code> in <code>content</code>
     * both starts and ends within <code>content</code>. Used to ensure that
     * it is always possible to construct anonymous blocks once an element's
     * children has been distributed among anonymous table objects.
     */
    private static void rebalanceInlineContent(List<Styleable> content) {
        Map<Element, InlineBox> boxesByElement = new HashMap<>();
        for (Styleable styleable : content) {
            if (styleable instanceof InlineBox) {
                InlineBox iB = (InlineBox) styleable;
                Element elem = iB.getElement();

                if (!boxesByElement.containsKey(elem)) {
                    iB.setStartsHere(true);
                }

                boxesByElement.put(elem, iB);
            }
        }

        for (InlineBox iB : boxesByElement.values()) {
            iB.setEndsHere(true);
        }
    }

    private static void stripAllWhitespace(List<Styleable> content) {
        int start = 0;
        int current = 0;
        boolean started = false;
        for (current = 0; current < content.size(); current++) {
            Styleable styleable = content.get(current);
            if (! styleable.getStyle().isLayedOutInInlineContext()) {
                if (started) {
                    int before = content.size();
                    WhitespaceStripper.stripInlineContent(content.subList(start, current));
                    int after = content.size();
                    current -= (before - after);
                }
                started = false;
            } else {
                if (! started) {
                    started = true;
                    start = current;
                }
            }
        }

        if (started) {
            WhitespaceStripper.stripInlineContent(content.subList(start, current));
        }
    }

    /**
     * Handles the situation when our current parent is table related.  If
     * everything is properly nested (e.g. a <code>tr</code> contains only
     * <code>td</code> elements), nothing is done.  Otherwise anonymous boxes
     * are inserted to ensure the integrity of the table model.
     */
    private static void resolveTableContent(
            LayoutContext c, BlockBox parent, List<Styleable> children, ChildBoxInfo info) {
        IdentValue parentDisplay = parent.getStyle().getIdent(CSSName.DISPLAY);
        IdentValue next = getNextTableNestingLevel(parentDisplay);
        if (next == null && parent.isAnonymous() && containsOrphanedTableContent(children)) {
            resolveChildTableContent(c, parent, children, info, IdentValue.TABLE_CELL);
        } else if (next == null || isAllProperTableNesting(parentDisplay, children)) {
            if (parent.isAnonymous()) {
                rebalanceInlineContent(children);
            }
            resolveChildren(c, parent, children, info);
        } else {
            List<Styleable> childrenForAnonymous = new ArrayList<>();
            List<Styleable> childrenWithAnonymous = new ArrayList<>();
            
            for (Styleable child : children) {
                IdentValue childDisplay = child.getStyle().getIdent(CSSName.DISPLAY);

                if (isProperTableNesting(parentDisplay, childDisplay)) {
                    if (childrenForAnonymous.size() > 0) {
                        createAnonymousTableContent(c, parent, next, childrenForAnonymous,
                                childrenWithAnonymous);

                        childrenForAnonymous = new ArrayList<>();
                    }
                    childrenWithAnonymous.add(child);
                } else {
                    childrenForAnonymous.add(child);
                }
            }

            if (childrenForAnonymous.size() > 0) {
                createAnonymousTableContent(c, parent, next, childrenForAnonymous,
                        childrenWithAnonymous);
            }

            info.setContainsBlockLevelContent(true);
            resolveChildren(c, parent, childrenWithAnonymous, info);
        }
    }
    
    private static boolean isTableRowOrRowGroup(Styleable child) {
        IdentValue display = child.getStyle().getIdent(CSSName.DISPLAY);
        return (display == IdentValue.TABLE_HEADER_GROUP ||
                display == IdentValue.TABLE_ROW_GROUP ||
                display == IdentValue.TABLE_FOOTER_GROUP ||
                display == IdentValue.TABLE_ROW);
    }

    private static boolean containsOrphanedTableContent(List<Styleable> children) {
        return children.stream().anyMatch(BoxBuilder::isTableRowOrRowGroup);
    }

    private static boolean isParentInline(BlockBox box) {
        CalculatedStyle parentStyle = box.getStyle().getParent();
        return parentStyle != null && parentStyle.isInline();
    }

    private static void createAnonymousTableContent(LayoutContext c, BlockBox source,
                                                    IdentValue next, List<Styleable> childrenForAnonymous, List<Styleable> childrenWithAnonymous) {
        ChildBoxInfo nested = lookForBlockContent(childrenForAnonymous);
        IdentValue anonDisplay;
        if (isParentInline(source) && next == IdentValue.TABLE) {
            anonDisplay = IdentValue.INLINE_TABLE;
        } else {
            anonDisplay = next;
        }
        CalculatedStyle anonStyle = source.getStyle().createAnonymousStyle(anonDisplay);
        BlockBox anonBox = createBlockBox(anonStyle, nested, false);
        anonBox.setStyle(anonStyle);
        anonBox.setAnonymous(true);
        // XXX Doesn't really make sense, but what to do?
        anonBox.setElement(source.getElement());
        resolveTableContent(c, anonBox, childrenForAnonymous, nested);

        if (next == IdentValue.TABLE) {
            childrenWithAnonymous.add(reorderTableContent(c, (TableBox) anonBox));
        } else {
            childrenWithAnonymous.add(anonBox);
        }
    }

    /**
     * Reorganizes a table so that the header is the first row group and the
     * footer the last.  If the table has caption boxes, they will be pulled
     * out and added to an anonymous block box along with the table itself.
     * If not, the table is returned.
     */
    private static BlockBox reorderTableContent(LayoutContext c, TableBox table) {
        List<Box> topCaptions = new ArrayList<>();
        Box header = null;
        List<Box> bodies = new ArrayList<>();
        Box footer = null;
        List<Box> bottomCaptions = new ArrayList<>();

        for (Box b : table.getChildren()) {
            IdentValue display = b.getStyle().getIdent(CSSName.DISPLAY);
            
            if (display == IdentValue.TABLE_CAPTION) {
                IdentValue side = b.getStyle().getIdent(CSSName.CAPTION_SIDE);
                if (side == IdentValue.BOTTOM) {
                    bottomCaptions.add(b);
                } else { /* side == IdentValue.TOP */
                    topCaptions.add(b);
                }
            } else if (display == IdentValue.TABLE_HEADER_GROUP && header == null) {
                header = b;
            } else if (display == IdentValue.TABLE_FOOTER_GROUP && footer == null) {
                footer = b;
            } else {
                bodies.add(b);
            }
        }

        table.removeAllChildren();
        if (header != null) {
            ((TableSectionBox)header).setHeader(true);
            table.addChild(header);
        }
        table.addAllChildren(bodies);
        if (footer != null) {
            ((TableSectionBox)footer).setFooter(true);
            table.addChild(footer);
        }

        if (topCaptions.size() == 0 && bottomCaptions.size() == 0) {
            return table;
        } else {
            // If we have a floated table with a caption, we need to float the
            // outer anonymous box and not the table
            CalculatedStyle anonStyle;
            if (table.getStyle().isFloated()) {
                CascadedStyle cascadedStyle = CascadedStyle.createLayoutStyle(
                        new PropertyDeclaration[]{
                                CascadedStyle.createLayoutPropertyDeclaration(
                                        CSSName.DISPLAY, IdentValue.BLOCK),
                                CascadedStyle.createLayoutPropertyDeclaration(
                                        CSSName.FLOAT, table.getStyle().getIdent(CSSName.FLOAT))});

                anonStyle = table.getStyle().deriveStyle(cascadedStyle);
            } else {
                anonStyle = table.getStyle().createAnonymousStyle(IdentValue.BLOCK);
            }

            BlockBox anonBox = new BlockBox();
            anonBox.setStyle(anonStyle);
            anonBox.setAnonymous(true);
            anonBox.setFromCaptionedTable(true);
            anonBox.setElement(table.getElement());

            anonBox.setChildrenContentType(BlockBox.CONTENT_BLOCK);
            anonBox.addAllChildren(topCaptions);
            anonBox.addChild(table);
            anonBox.addAllChildren(bottomCaptions);

            if (table.getStyle().isFloated()) {
                anonBox.setFloatedBoxData(new FloatedBoxData());
                table.setFloatedBoxData(null);

                CascadedStyle original = c.getSharedContext().getCss().getCascadedStyle(
                        table.getElement(), false);
                CascadedStyle modified = CascadedStyle.createLayoutStyle(
                        original,
                        new PropertyDeclaration[]{
                                CascadedStyle.createLayoutPropertyDeclaration(
                                        CSSName.FLOAT, IdentValue.NONE)
                        });
                table.setStyle(table.getStyle().getParent().deriveStyle(modified));
            }

            return anonBox;
        }
    }

    private static ChildBoxInfo lookForBlockContent(List<Styleable> styleables) {
        ChildBoxInfo result = new ChildBoxInfo();
        
        if (styleables.stream().anyMatch(s -> !s.getStyle().isLayedOutInInlineContext())) {
            result.setContainsBlockLevelContent(true);
        }
        
        return result;
    }

    private static IdentValue getNextTableNestingLevel(IdentValue display) {
        if (display == IdentValue.TABLE || display == IdentValue.INLINE_TABLE) {
            return IdentValue.TABLE_ROW_GROUP;
        } else if (display == IdentValue.TABLE_HEADER_GROUP
                || display == IdentValue.TABLE_ROW_GROUP
                || display == IdentValue.TABLE_FOOTER_GROUP) {
            return IdentValue.TABLE_ROW;
        } else if (display == IdentValue.TABLE_ROW) {
            return IdentValue.TABLE_CELL;
        } else {
            return null;
        }
    }

    private static IdentValue getPreviousTableNestingLevel(IdentValue display) {
        if (display == IdentValue.TABLE_CELL) {
            return IdentValue.TABLE_ROW;
        } else if (display == IdentValue.TABLE_ROW) {
            return IdentValue.TABLE_ROW_GROUP;
        } else if (display == IdentValue.TABLE_HEADER_GROUP
                || display == IdentValue.TABLE_ROW_GROUP
                || display == IdentValue.TABLE_FOOTER_GROUP) {
            return IdentValue.TABLE;
        } else {
            return null;
        }
    }

    private static boolean isProperTableNesting(IdentValue parent, IdentValue child) {
        return (parent == IdentValue.TABLE && (child == IdentValue.TABLE_HEADER_GROUP ||
                child == IdentValue.TABLE_ROW_GROUP ||
                child == IdentValue.TABLE_FOOTER_GROUP ||
                child == IdentValue.TABLE_CAPTION))
                || ((parent == IdentValue.TABLE_HEADER_GROUP ||
                parent == IdentValue.TABLE_ROW_GROUP ||
                parent == IdentValue.TABLE_FOOTER_GROUP) &&
                child == IdentValue.TABLE_ROW)
                || (parent == IdentValue.TABLE_ROW && child == IdentValue.TABLE_CELL)
                || (parent == IdentValue.INLINE_TABLE && (child == IdentValue.TABLE_HEADER_GROUP ||
                child == IdentValue.TABLE_ROW_GROUP ||
                child == IdentValue.TABLE_FOOTER_GROUP));

    }

    private static boolean isNestingTableContent(IdentValue display) {
        return display == IdentValue.TABLE || display == IdentValue.INLINE_TABLE ||
                display == IdentValue.TABLE_HEADER_GROUP || display == IdentValue.TABLE_ROW_GROUP ||
                display == IdentValue.TABLE_FOOTER_GROUP || display == IdentValue.TABLE_ROW;
    }

    private static boolean isAttrFunction(FSFunction function) {
        if (function.getName().equals("attr")) {
            List<PropertyValue> params = function.getParameters();
            if (params.size() == 1) {
                PropertyValue value = params.get(0);
                return value.getPrimitiveType() == CSSPrimitiveValue.CSS_IDENT;
            }
        }

        return false;
    }

    public static boolean isElementFunction(FSFunction function) {
        if (function.getName().equals("element")) {
            List<PropertyValue> params = function.getParameters();
            if (params.size() < 1 || params.size() > 2) {
                return false;
            }
            boolean ok = true;
            PropertyValue value1 = params.get(0);
            ok = value1.getPrimitiveType() == CSSPrimitiveValue.CSS_IDENT;
            if (ok && params.size() == 2) {
                PropertyValue value2 = params.get(1);
                ok = value2.getPrimitiveType() == CSSPrimitiveValue.CSS_IDENT;
            }

            return ok;
        }

        return false;
    }

    private static CounterFunction makeCounterFunction(
            FSFunction function, LayoutContext c, CalculatedStyle style) {

        if (function.getName().equals("counter")) {
            List<PropertyValue> params = function.getParameters();
            if (params.size() < 1 || params.size() > 2) {
                return null;
            }

            PropertyValue value = params.get(0);
            if (value.getPrimitiveType() != CSSPrimitiveValue.CSS_IDENT) {
                return null;
            }

            String s = value.getStringValue();
            // counter(page) and counter(pages) are handled separately
            if (s.equals("page") || s.equals("pages")) {
                return null;
            }

            String counter = value.getStringValue();
            IdentValue listStyleType = IdentValue.DECIMAL;
            if (params.size() == 2) {
                value = params.get(1);
                if (value.getPrimitiveType() != CSSPrimitiveValue.CSS_IDENT) {
                    return null;
                }

                IdentValue identValue = IdentValue.valueOf(value.getStringValue());
                if (identValue != null) {
                    value.setIdentValue(identValue);
                    listStyleType = identValue;
                }
            }

            if ("footnote".equals(s)) {
                RootCounterContext rootCc = c.getSharedContext().getGlobalCounterContext();

                int counterValue = rootCc.getCurrentCounterValue(s);
                return new CounterFunction(counterValue, listStyleType);
            }

            AbstractCounterContext cc = c.getCounterContext(style);

            int counterValue = cc.getCurrentCounterValue(counter);

            return new CounterFunction(counterValue, listStyleType);
        } else if (function.getName().equals("counters")) {
            List<PropertyValue> params = function.getParameters();
            if (params.size() < 2 || params.size() > 3) {
                return null;
            }

            PropertyValue value = params.get(0);
            if (value.getPrimitiveType() != CSSPrimitiveValue.CSS_IDENT) {
                return null;
            }

            String counter = value.getStringValue();

            value = params.get(1);
            if (value.getPrimitiveType() != CSSPrimitiveValue.CSS_STRING) {
                return null;
            }

            String separator = value.getStringValue();

            IdentValue listStyleType = IdentValue.DECIMAL;
            if (params.size() == 3) {
                value = params.get(2);
                if (value.getPrimitiveType() != CSSPrimitiveValue.CSS_IDENT) {
                    return null;
                }

                IdentValue identValue = IdentValue.valueOf(value.getStringValue());
                if (identValue != null) {
                    value.setIdentValue(identValue);
                    listStyleType = identValue;
                }
            }

            List<Integer> counterValues = c.getCounterContext(style).getCurrentCounterValues(counter);

            return new CounterFunction(counterValues, separator, listStyleType);
        } else {
            return null;
        }
    }

    private static String getAttributeValue(FSFunction attrFunc, Element e) {
        PropertyValue value = attrFunc.getParameters().get(0);
        return e.getAttribute(value.getStringValue());
    }

    private static List<Styleable> createGeneratedContentList(
            LayoutContext c, Element element, PropertyValue propValue,
            String peName, CalculatedStyle style, int mode, ChildBoxInfo info) {
        List<PropertyValue> values = propValue.getValues();

        if (values == null) {
            // content: normal or content: none
            return Collections.emptyList();
        }

        List<Styleable> result = new ArrayList<>(values.size());

        for (PropertyValue value : values) {
            ContentFunction contentFunction = null;
            FSFunction function = null;

            String content = null;

            short type = value.getPrimitiveType();
            if (type == CSSPrimitiveValue.CSS_STRING) {
                content = value.getStringValue();
            } else if (type == CSSPrimitiveValue.CSS_URI) {
                Element creator = element != null ? element : c.getRootLayer().getMaster().getElement();
                Document doc = creator.getOwnerDocument();
                Element img = doc.createElement("img");
                img.setAttribute("src", value.getStringValue());
                creator.appendChild(img);

                BlockBox iB = new BlockBox();
                iB.setElement(img);
                CalculatedStyle anon = new EmptyStyle().createAnonymousStyle(IdentValue.INLINE_BLOCK);
                iB.setStyle(anon);

                info.setContainsBlockLevelContent(true);

                result.add(iB);
            } else if (value.getPropertyValueType() == PropertyValue.VALUE_TYPE_FUNCTION) {
                if (mode == CONTENT_LIST_DOCUMENT && isAttrFunction(value.getFunction())) {
                    content = getAttributeValue(value.getFunction(), element);
                } else {
                    CounterFunction cFunc = null;

                    if (mode == CONTENT_LIST_DOCUMENT) {
                        cFunc = makeCounterFunction(value.getFunction(), c, style);
                    }

                    if (cFunc != null) {
                        //TODO: counter functions may be called with non-ordered list-style-types, e.g. disc
                        content = cFunc.evaluate();
                        contentFunction = null;
                        function = null;
                    } else if (mode == CONTENT_LIST_MARGIN_BOX && isElementFunction(value.getFunction())) {
                        BlockBox target = getRunningBlock(c, value);
                        if (target != null) {
                            result.add(target.copyOf());
                            info.setContainsBlockLevelContent(true);
                        }
                    } else {
                        contentFunction =
                                c.getContentFunctionFactory().lookupFunction(c, value.getFunction());
                        if (contentFunction != null) {
                            function = value.getFunction();

                            if (contentFunction.isStatic()) {
                                content = contentFunction.calculate(c, function);
                                contentFunction = null;
                                function = null;
                            } else {
                                content = contentFunction.getLayoutReplacementText();
                            }
                        }
                    }
                }
            } else if (type == CSSPrimitiveValue.CSS_IDENT) {
                FSDerivedValue dv = style.valueByName(CSSName.QUOTES);

                if (dv != IdentValue.NONE) {
                    IdentValue ident = value.getIdentValue();

                    if (ident == IdentValue.OPEN_QUOTE) {
                        String[] quotes = style.asStringArray(CSSName.QUOTES);
                        content = quotes[0];
                    } else if (ident == IdentValue.CLOSE_QUOTE) {
                        String[] quotes = style.asStringArray(CSSName.QUOTES);
                        content = quotes[1];
                    }
                }
            }

            if (content != null) {
                InlineBox iB = new InlineBox(content);
                iB.setContentFunction(contentFunction);
                iB.setFunction(function);
                iB.setElement(element);
                iB.setPseudoElementOrClass(peName);
                iB.setStartsHere(true);
                iB.setEndsHere(true);

                result.add(iB);
            }
        }

        return result;
    }

    public static BlockBox getRunningBlock(LayoutContext c, PropertyValue value) {
        List<PropertyValue> params = value.getFunction().getParameters();
        String ident = params.get(0).getStringValue();
        PageElementPosition position = null;
        if (params.size() == 2) {
            position = PageElementPosition.valueOf(params.get(1).getStringValue());
        }
        if (position == null) {
            position = PageElementPosition.FIRST;
        }
        BlockBox target = c.getRootDocumentLayer().getRunningBlock(ident, c.getPage(), position);
        return target;
    }

    private static void insertGeneratedContent(
            LayoutContext c, Element element, CalculatedStyle parentStyle,
            String peName, List<Styleable> children, ChildBoxInfo info) {

        CascadedStyle peStyle = c.getCss().getPseudoElementStyle(element, peName);

        if (peStyle != null) {
            PropertyDeclaration contentDecl = peStyle.propertyByName(CSSName.CONTENT);
            PropertyDeclaration counterResetDecl = peStyle.propertyByName(CSSName.COUNTER_RESET);
            PropertyDeclaration counterIncrDecl = peStyle.propertyByName(CSSName.COUNTER_INCREMENT);

            CalculatedStyle calculatedStyle = null;

            if (contentDecl != null || counterResetDecl != null || counterIncrDecl != null) {
                calculatedStyle = parentStyle.deriveStyle(peStyle);
                if (calculatedStyle.isDisplayNone()) return;
                if (calculatedStyle.isIdent(CSSName.CONTENT, IdentValue.NONE)) return;
                if (calculatedStyle.isIdent(CSSName.CONTENT, IdentValue.NORMAL) && (peName.equals("before") || peName.equals("after")))
                    return;

                if (calculatedStyle.isTable() || calculatedStyle.isTableRow() || calculatedStyle.isTableSection()) {
                    CascadedStyle newPeStyle =
                        CascadedStyle.createLayoutStyle(peStyle, new PropertyDeclaration[] {
                            CascadedStyle.createLayoutPropertyDeclaration(
                                CSSName.DISPLAY,
                                IdentValue.BLOCK),
                        });
                    calculatedStyle = parentStyle.deriveStyle(newPeStyle);
                }

                c.resolveCounters(calculatedStyle);
            }

            if (contentDecl != null) {
                CSSPrimitiveValue propValue = contentDecl.getValue();
                children.addAll(createGeneratedContent(c, element, peName, calculatedStyle,
                        (PropertyValue) propValue, info));
            }
        }
    }

    private static List<Styleable> createGeneratedContent(
            LayoutContext c, Element element, String peName,
            CalculatedStyle style, PropertyValue property, ChildBoxInfo info) {
        if (style.isDisplayNone() || style.isIdent(CSSName.DISPLAY, IdentValue.TABLE_COLUMN)
                || style.isIdent(CSSName.DISPLAY, IdentValue.TABLE_COLUMN_GROUP)) {
            return Collections.emptyList();
        }

        ChildBoxInfo childInfo = new ChildBoxInfo();
        List<Styleable> inlineBoxes = createGeneratedContentList(
                c, element, property, peName, style, CONTENT_LIST_DOCUMENT, childInfo);

        if (childInfo.isContainsBlockLevelContent()) {
            List<Styleable> inlines = new ArrayList<>();

            CalculatedStyle anonStyle = style.isInlineBlock() || style.isInline() ?
                           style : style.createAnonymousStyle(IdentValue.INLINE_BLOCK);

            BlockBox result = createBlockBox(style, info, true);
            result.setStyle(anonStyle);
            result.setElement(element);
            result.setChildrenContentType(BlockBox.CONTENT_INLINE);
            result.setPseudoElementOrClass(peName);

            CalculatedStyle anon = style.createAnonymousStyle(IdentValue.INLINE);
            for (Iterator<Styleable> i = inlineBoxes.iterator(); i.hasNext();) {
               Styleable b = i.next();

               if (b instanceof BlockBox) {
                   inlines.add(b);
               } else {
                   InlineBox iB = (InlineBox) b;

                   iB.setStyle(anon);
                   iB.applyTextTransform();
                   iB.setElement(null);

                   inlines.add(iB);
               }
            }

            if (!inlines.isEmpty()) {
                result.setInlineContent(inlines);
            }
            return Collections.singletonList(result);
        } else if (style.isInline()) {
            for (Iterator<Styleable> i = inlineBoxes.iterator(); i.hasNext();) {
                InlineBox iB = (InlineBox) i.next();
                iB.setStyle(style);
                iB.applyTextTransform();
            }
            return inlineBoxes;
        } else {
            CalculatedStyle anon = style.createAnonymousStyle(IdentValue.INLINE);
            for (Iterator<Styleable> i = inlineBoxes.iterator(); i.hasNext();) {
                InlineBox iB = (InlineBox) i.next();
                iB.setStyle(anon);
                iB.applyTextTransform();
                iB.setElement(null);
            }

            BlockBox result = createBlockBox(style, info, true);
            result.setStyle(style);
            result.setInlineContent(inlineBoxes);
            result.setElement(element);
            result.setChildrenContentType(BlockBox.CONTENT_INLINE);
            result.setPseudoElementOrClass(peName);

            if (! style.isLayedOutInInlineContext()) {
                info.setContainsBlockLevelContent(true);
            }

            return new ArrayList<>(Collections.singletonList(result));
        }
    }

    private static List<Styleable> createGeneratedMarginBoxContent(
            LayoutContext c, Element element, PropertyValue property,
            CalculatedStyle style, ChildBoxInfo info) {
        
        List<Styleable> result = createGeneratedContentList(
                c, element, property, null, style, CONTENT_LIST_MARGIN_BOX, info);

        CalculatedStyle anon = style.createAnonymousStyle(IdentValue.INLINE);
        for (Styleable s : result) {
            if (s instanceof InlineBox) {
                InlineBox iB = (InlineBox)s;
                iB.setElement(null);
                iB.setStyle(anon);
                iB.applyTextTransform();
            }
        }

        return result;
    }

    private static BlockBox createBlockBox(
            CalculatedStyle style, ChildBoxInfo info, boolean generated) {
        if (style.isFootnote()) {
            BlockBox result = new BlockBox();
            return result;
        } else if (style.isFloated() && !(style.isAbsolute() || style.isFixed())) {
            BlockBox result;
            if (style.isTable() || style.isInlineTable()) {
                result = new TableBox();
            } else if (style.isTableCell()) {
                info.setContainsTableContent(true);
                result = new TableCellBox();
            } else {
                result = new BlockBox();
            }
            result.setFloatedBoxData(new FloatedBoxData());
            return result;
        } else if (style.isSpecifiedAsBlock()) {
            return new BlockBox();
        } else if (! generated && (style.isTable() || style.isInlineTable())) {
            return new TableBox();
        } else if (style.isTableCell()) {
            info.setContainsTableContent(true);
            return new TableCellBox();
        } else if (! generated && style.isTableRow()) {
            info.setContainsTableContent(true);
            return new TableRowBox();
        } else if (! generated && style.isTableSection()) {
            info.setContainsTableContent(true);
            return new TableSectionBox();
        } else if (style.isTableCaption()) {
            info.setContainsTableContent(true);
            return new BlockBox();
        } else {
            return new BlockBox();
        }
    }

    private static void addColumns(LayoutContext c, TableBox table, TableColumn parent) {
        SharedContext sharedContext = c.getSharedContext();

        Node working = parent.getElement().getFirstChild();
        boolean found = false;
        while (working != null) {
            if (working.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) working;
                CalculatedStyle style = sharedContext.getStyle(element);

                if (style.isIdent(CSSName.DISPLAY, IdentValue.TABLE_COLUMN)) {
                    found = true;
                    TableColumn col = new TableColumn(element, style);
                    col.setParent(parent);
                    table.addStyleColumn(col);
                }
            }
            working = working.getNextSibling();
        }
        if (! found) {
            table.addStyleColumn(parent);
        }
    }

    private static void addColumnOrColumnGroup(
            LayoutContext c, TableBox table, Element e, CalculatedStyle style) {
        if (style.isIdent(CSSName.DISPLAY, IdentValue.TABLE_COLUMN)) {
            table.addStyleColumn(new TableColumn(e, style));
        } else { /* style.isIdent(CSSName.DISPLAY, IdentValue.TABLE_COLUMN_GROUP) */
            addColumns(c, table, new TableColumn(e, style));
        }
    }

    private static InlineBox createInlineBox(
            String text, Element parent, CalculatedStyle parentStyle, Text node) {
        InlineBox result = new InlineBox(text);

        if (parentStyle.isInline() && ! (parent.getParentNode() instanceof Document)) {
            result.setStyle(parentStyle);
            result.setElement(parent);
        } else {
            result.setStyle(parentStyle.createAnonymousStyle(IdentValue.INLINE));
        }

        result.applyTextTransform();

        return result;
    }

    private static class CreateChildrenContext {
        CreateChildrenContext(
            boolean needStartText, boolean needEndText,
            CalculatedStyle parentStyle, boolean inline) {
            this.needStartText = needStartText;
            this.needEndText = needEndText;
            this.parentStyle = parentStyle;
            this.inline = inline;
        }

        boolean needStartText;
        boolean needEndText;
        boolean inline;

        InlineBox previousIB = null;
        final CalculatedStyle parentStyle;
    }

    private static void createElementChild(
            LayoutContext c,
            Element parent,
            BlockBox blockParent,
            Node working,
            List<Styleable> children,
            ChildBoxInfo info,
            CreateChildrenContext context) {

        Styleable child = null;
        SharedContext sharedContext = c.getSharedContext();
        Element element = (Element) working;
        CalculatedStyle style = sharedContext.getStyle(element);

        if (style.isDisplayNone()) {
            return;
        }

        resolveElementCounters(c, working, element, style);

        if (style.isIdent(CSSName.DISPLAY, IdentValue.TABLE_COLUMN) ||
            style.isIdent(CSSName.DISPLAY, IdentValue.TABLE_COLUMN_GROUP)) {

            if ((blockParent != null) &&
                (blockParent.getStyle().isTable() || blockParent.getStyle().isInlineTable())) {
                TableBox table = (TableBox) blockParent;
                addColumnOrColumnGroup(c, table, element, style);
            }

            return;
        }

        if (style.isFootnote()) {
            List<Styleable> footnoteChildren = new ArrayList<>();
            ChildBoxInfo footnoteChildInfo = new ChildBoxInfo();

            // Create the out-of-flow footnote-body box as a block box.
            BlockBox footnoteBody = new BlockBox();

            footnoteBody.setElement(element);
            footnoteBody.setStyle(style.createAnonymousStyle(IdentValue.BLOCK));
            footnoteBody.setChildrenContentType(BlockBox.CONTENT_INLINE);
            footnoteBody.setContainingBlock(c.getFootnoteLayer().getMaster().getContainingBlock());

            Layer layer = new Layer(footnoteBody, c, true);

            footnoteBody.setLayer(layer);
            footnoteBody.setContainingLayer(layer);
            c.pushLayer(layer);

            // The footnote marker followed by footnote element children.
            insertGeneratedContent(c, element, style, "footnote-marker", footnoteChildren, footnoteChildInfo);
            createChildren(c, footnoteBody, element, footnoteChildren, footnoteChildInfo, style.isInline());

            footnoteBody.setInlineContent(footnoteChildren);

            footnoteChildInfo.setContainsBlockLevelContent(false);

            c.popLayer();

            // This is purely a marker box for the footnote so we
            // can figure out in layout when to add the footnote body.
            InlineBox iB = createInlineBox("", parent, context.parentStyle, null);
            iB.setStartsHere(true);
            iB.setEndsHere(true);
            iB.setFootnote(footnoteBody);
            children.add(iB);

            // This is the official marker content that can generate zero or more boxes
            // depending on user for ::footnote-call pseudo element.
            insertGeneratedContent(c, element, style, "footnote-call", children, info);

        } else if (style.isInline()) {

            if (context.needStartText) {
                context.needStartText = false;
                InlineBox iB = createInlineBox("", parent, context.parentStyle, null);
                iB.setStartsHere(true);
                iB.setEndsHere(false);
                children.add(iB);
                context.previousIB = iB;
            }

            createChildren(c, null, element, children, info, true);

            if (context.inline) {
                if (context.previousIB != null) {
                    context.previousIB.setEndsHere(false);
                }
                context.needEndText = true;
            }
        } else {
            if (style.hasColumns() && c.isPrint()) {
                child = new FlowingColumnContainerBox();
            } else {
                child = createBlockBox(style, info, false);
            }

            child.setStyle(style);
            child.setElement(element);

            if (style.hasColumns() && c.isPrint()) {
                createColumnContainer(c, child, element, style);
            }

            if (style.isListItem()) {
                BlockBox block = (BlockBox) child;
                block.setListCounter(c.getCounterContext(style).getCurrentCounterValue("list-item"));
            }

            if (style.isTable() || style.isInlineTable()) {
                TableBox table = (TableBox) child;
                table.ensureChildren(c);

                child = reorderTableContent(c, table);
            }

            if (!info.isContainsBlockLevelContent()
                    && !style.isLayedOutInInlineContext()) {
                info.setContainsBlockLevelContent(true);
            }

            BlockBox block = (BlockBox) child;

            if (block.getStyle().mayHaveFirstLine()) {
                block.setFirstLineStyle(c.getCss().getPseudoElementStyle(element,
                        "first-line"));
            }
            if (block.getStyle().mayHaveFirstLetter()) {
                block.setFirstLetterStyle(c.getCss().getPseudoElementStyle(element,
                        "first-letter"));
            }

            // I think we need to do this to evaluate counters correctly
            block.ensureChildren(c);
        }

        if (child != null) {
            children.add(child);
        }
    }

    private static void createColumnContainer(
         LayoutContext c, Styleable child, Element element, CalculatedStyle style) {

        FlowingColumnContainerBox cont = (FlowingColumnContainerBox) child;
        cont.setOnlyChild(c, new FlowingColumnBox(cont));
        cont.getChild().setStyle(style.createAnonymousStyle(IdentValue.BLOCK));
        cont.getChild().setElement(element);
        cont.getChild().ensureChildren(c);
    }

    private static void resolveElementCounters(
         LayoutContext c, Node working, Element element, CalculatedStyle style) {

        Integer attrValue = null;

        if ("ol".equals(working.getNodeName()) && element.hasAttribute("start")) {
            attrValue = OpenUtil.parseIntegerOrNull(element.getAttribute("start"));
        } else if ("li".equals(working.getNodeName()) && element.hasAttribute("value")) {
            attrValue = OpenUtil.parseIntegerOrNull(element.getAttribute("value"));
        }

        if (attrValue != null) {
            c.resolveCounters(style, attrValue - 1);
        } else {
            c.resolveCounters(style, null);
        }
    }

    private static void createChildren(
            LayoutContext c, BlockBox blockParent, Element parent,
            List<Styleable> children, ChildBoxInfo info, boolean inline) {

        SharedContext sharedContext = c.getSharedContext();
        CalculatedStyle parentStyle = sharedContext.getStyle(parent);

        insertGeneratedContent(c, parent, parentStyle, "before", children, info);

        Node working = parent.getFirstChild();
        CreateChildrenContext context = null;

        if (working != null) {
            context = new CreateChildrenContext(inline, inline, parentStyle, inline);

            do {
                short nodeType = working.getNodeType();

                if (nodeType == Node.ELEMENT_NODE) {
                    createElementChild(
                            c, parent, blockParent, working, children, info, context);
                } else if (nodeType == Node.TEXT_NODE || nodeType == Node.CDATA_SECTION_NODE) {
                    context.needStartText = false;
                    context.needEndText = false;

                    Text textNode = (Text) working;

                    // Ignore the text belonging to a textarea.
                    if (!textNode.getParentNode().getNodeName().equals("textarea")) {
                        context.previousIB = doBidi(c, textNode, parent, parentStyle, context.previousIB, children);
                    }
                }
            } while ((working = working.getNextSibling()) != null);
        }

        boolean needStartText = context != null ? context.needStartText : inline;
        boolean needEndText = context != null ? context.needEndText : inline;

        if (needStartText || needEndText) {
            InlineBox iB = createInlineBox("", parent, parentStyle, null);
            iB.setStartsHere(needStartText);
            iB.setEndsHere(needEndText);
            children.add(iB);
        }

        insertGeneratedContent(c, parent, parentStyle, "after", children, info);
    }

    private static InlineBox setupInlineChild(InlineBox child, InlineBox previousIB) {
        child.setEndsHere(true);
        
        if (previousIB == null) {
            child.setStartsHere(true);
        } else {
            previousIB.setEndsHere(false);
        }
        
        return child;
    }
    
    private static InlineBox doFakeBidi(LayoutContext c, Text textNode, Element parent, CalculatedStyle parentStyle, InlineBox previousIB, List<Styleable> children) {
    	String runText = textNode.getData();
    	InlineBox child = createInlineBox(runText, parent, parentStyle, textNode);
    	child.setTextDirection(BidiSplitter.LTR);
    	previousIB = setupInlineChild(child, previousIB);
       	children.add(child);
       	return previousIB;
    }
    
    
    /**
     * Attempts to divide a Text node further into directional text runs, either LTR or RTL. 
     * @param c
     * @param textNode
     * @param parent
     * @param parentStyle
     * @return the previousIB.
     */
    private static InlineBox doBidi(LayoutContext c, Text textNode, Element parent, CalculatedStyle parentStyle, InlineBox previousIB, List<Styleable> children) {
    	
        Paragraph para = c.getParagraphSplitter().lookupParagraph(textNode);
        if (para == null) {
        	// Must be no implementation of BIDI for this Text node.
        	return doFakeBidi(c, textNode, parent, parentStyle, previousIB, children);
        }
        
        int startIndex = para.getFirstCharIndexInParagraph(textNode); // Index into the paragraph.
        
        if (startIndex < 0) {
        	// Must be a fake implementation of BIDI.
        	return doFakeBidi(c, textNode, parent, parentStyle, previousIB, children);
        }
        
        int nodeIndex = 0;                                            // Index into the text node.
        String runText;                                               // Calculated text for the directional run.
        
        BidiTextRun prevSplit = para.prevSplit(startIndex); // Get directional run at or before startIndex.
        	
        assert(prevSplit != null);                  // There should always be a split at zero (start of paragraph) to fall back on. 
        assert(prevSplit.getStart() <= startIndex); // Split should always be before or at the start of this text node.

        // When calculating length, remember that it may overlap the start and/or end of the text node.
        int maxRunLength = prevSplit.getLength() - (startIndex - prevSplit.getStart());
       	int splitLength = Math.min(maxRunLength, textNode.getLength());
        
       	// Advance char indexes.
       	nodeIndex += splitLength;
       	startIndex += splitLength;
       	
       	assert(prevSplit.getDirection() == BidiSplitter.LTR || prevSplit.getDirection() == BidiSplitter.RTL);

       	if (splitLength == textNode.getLength()) {
       		// The simple case: the entire text node is part of a single direction run.
       		runText = textNode.getData();
       	}
       	else {
       		// The complex case: the first directional run only encompasses part of the text node. 
       		runText = textNode.getData().substring(0, nodeIndex);
       	}
       	
		// Shape here, so the layout will get the right visual length for the run.
		if (prevSplit.getDirection() == BidiSplitter.RTL) {
			runText = c.getBidiReorderer().shapeText(runText);
		}

       	InlineBox child = createInlineBox(runText, parent, parentStyle, textNode);
       	child.setTextDirection(prevSplit.getDirection());
       	previousIB = setupInlineChild(child, previousIB);
       	children.add(child);
       	
       	if (splitLength != textNode.getLength()) {
       		// We have more directional runs to extract.
       		
       		do {
        		BidiTextRun newSplit = para.nextSplit(startIndex);
        		assert(newSplit != null); // There should always be enough splits to completely cover the text node.
        		
        		int newLength;
        		
        		if (newSplit != null) {
        			// When calculating length, remember that it may overlap the start and/or end of the text node.
        			int newMaxRunLength = newSplit.getLength() - (startIndex - newSplit.getStart());
        			newLength = Math.min(newMaxRunLength, textNode.getLength() - nodeIndex);
        			
        			runText = textNode.getData().substring(nodeIndex, nodeIndex + newLength);
        			
        			// Shape here, so the layout will get the right visual length for the run.
        			if (newSplit.getDirection() == BidiSplitter.RTL) {
        				runText = c.getBidiReorderer().shapeText(runText);
        			}
        			
        			startIndex += newLength;
        			nodeIndex += newLength;
        			
        			child = createInlineBox(runText, parent, parentStyle, textNode);
        			child.setTextDirection(newSplit.getDirection());
        	       	previousIB = setupInlineChild(child, previousIB);
        	       	children.add(child);
        		}
        		else {
        			// We should never get here, but handle it just in case.
        			
        			newLength = textNode.getLength() - nodeIndex;
        			runText = textNode.getData().substring(nodeIndex, newLength);
        			
        			child = createInlineBox(runText, parent, parentStyle, textNode);
        			child.setTextDirection(c.getDefaultTextDirection());
        	       	previousIB = setupInlineChild(child, previousIB);
        	       	children.add(child);

        			startIndex += newLength;
        			nodeIndex += newLength;
        		}
        	} while(nodeIndex < textNode.getLength());
        }
       	
       	return previousIB;
    }
    
    private static void insertAnonymousBlocks(
            SharedContext c, Box parent, List<Styleable> children, boolean layoutRunningBlocks) {

        List<Styleable> inline = new ArrayList<>();
        Deque<InlineBox> parents = new ArrayDeque<>();
        List<InlineBox> savedParents = null;

        for (Styleable child : children) {
            if (child.getStyle().isLayedOutInInlineContext() &&
                    ! (layoutRunningBlocks && child.getStyle().isRunning()) &&
                    !child.getStyle().isTableCell() //see issue https://github.com/danfickle/openhtmltopdf/issues/309
            ) {
                inline.add(child);

                if (child.getStyle().isInline()) {
                    InlineBox iB = (InlineBox) child;
                    if (iB.isStartsHere()) {
                        parents.add(iB);
                    }
                    if (iB.isEndsHere()) {
                        parents.removeLast();
                    }
                }
            } else {
                if (inline.size() > 0) {
                    createAnonymousBlock(c, parent, inline, savedParents);
                    inline = new ArrayList<>();
                    savedParents = new ArrayList<>(parents);
                }
                parent.addChild((Box) child);
            }
        }

        createAnonymousBlock(c, parent, inline, savedParents);
    }

    private static void createAnonymousBlock(SharedContext c, Box parent, List<Styleable> inline, List<InlineBox> savedParents) {
        createAnonymousBlock(c, parent, inline, savedParents, IdentValue.BLOCK);
    }

    private static void createAnonymousBlock(SharedContext c, Box parent, List<Styleable> inline, List<InlineBox> savedParents, IdentValue display) {
        WhitespaceStripper.stripInlineContent(inline);
        if (inline.size() > 0) {
            AnonymousBlockBox anon = new AnonymousBlockBox(parent.getElement());
            anon.setStyle(parent.getStyle().createAnonymousStyle(display));
            anon.setAnonymous(true);
            if (savedParents != null && savedParents.size() > 0) {
                anon.setOpenInlineBoxes(savedParents);
            }
            parent.addChild(anon);
            anon.setChildrenContentType(BlockBox.CONTENT_INLINE);
            anon.setInlineContent(inline);
        }
    }

    private static class ChildBoxInfo {
        private boolean _containsBlockLevelContent;
        private boolean _containsTableContent;
        private boolean _layoutRunningBlocks;

        public ChildBoxInfo() {
        }

        public boolean isContainsBlockLevelContent() {
            return _containsBlockLevelContent;
        }

        public void setContainsBlockLevelContent(boolean containsBlockLevelContent) {
            _containsBlockLevelContent = containsBlockLevelContent;
        }

        public boolean isContainsTableContent() {
            return _containsTableContent;
        }

        public void setContainsTableContent(boolean containsTableContent) {
            _containsTableContent = containsTableContent;
        }

        public boolean isLayoutRunningBlocks() {
            return _layoutRunningBlocks;
        }

        public void setLayoutRunningBlocks(boolean layoutRunningBlocks) {
            _layoutRunningBlocks = layoutRunningBlocks;
        }
    }
}
