/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 */
package org.opensolaris.opengrok.analysis.executables;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.ClassFormatException;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantCP;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantDouble;
import org.apache.bcel.classfile.ConstantFloat;
import org.apache.bcel.classfile.ConstantInteger;
import org.apache.bcel.classfile.ConstantLong;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.ConstantString;
import org.apache.bcel.classfile.ConstantUtf8;
import org.apache.bcel.classfile.ExceptionTable;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.LocalVariableTable;
import org.apache.bcel.classfile.Utility;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.TextField;
import org.opensolaris.opengrok.analysis.FileAnalyzer;
import org.opensolaris.opengrok.analysis.FileAnalyzerFactory;
import org.opensolaris.opengrok.analysis.IteratorReader;
import org.opensolaris.opengrok.analysis.StreamSource;
import org.opensolaris.opengrok.configuration.RuntimeEnvironment;

/**
 * Analyzes Java Class files Created on September 23, 2005
 *
 * @author Chandan
 * @author Lubos Kosco , January 2010 , updated bcel, comment on thread safety
 */
public class JavaClassAnalyzer extends FileAnalyzer {

    private final String urlPrefix = RuntimeEnvironment.getInstance().getUrlPrefix();

    /**
     * Creates a new instance of JavaClassAnalyzer
     *
     * @param factory The factory that creates JavaClassAnalyzers
     */
    protected JavaClassAnalyzer(FileAnalyzerFactory factory) {
        super(factory);
    }

    @Override
    public void analyze(Document doc, StreamSource src, Writer xrefOut) throws IOException {
        try (InputStream in = src.getStream()) {
            analyze(doc, in, xrefOut);
        }
    }

    void analyze(Document doc, InputStream in, Writer xrefOut) throws IOException {
        List<String> defs = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        List<String> full = new ArrayList<>();

        ClassParser classparser = new ClassParser(in, doc.get("path"));
        StringWriter out = new StringWriter();
        getContent(out, classparser.parse(), defs, refs, full);
        String xref = out.toString();

        if (xrefOut != null) {
            xrefOut.append(xref);
        }

        out.getBuffer().setLength(0); // clear the buffer
        for (String fl : full) {
            out.write(fl);
            out.write('\n');
        }
        String constants = out.toString();

        doc.add(new TextField("defs", new IteratorReader(defs)));
        doc.add(new TextField("refs", new IteratorReader(refs)));
        doc.add(new TextField("full", new HTMLStripCharFilter(new StringReader(xref))));
        doc.add(new TextField("full", constants, Store.NO));
    }

    protected String linkPath(String path) {
        return "<a href=\"" + urlPrefix + "path=" + path + "\">" + path + "</a>";
    }

    protected String linkDef(String def) {
        return "<a href=\"" + urlPrefix + "defs=" + def + "\">" + def + "</a>";
    }

    protected String tagDef(String def) {
        return "<a class=\"d\" name=\"" + def + "\" href=\"" + urlPrefix + "defs=" + def + "\">" + def + "</a>";
    }

//TODO this class needs to be thread safe to avoid bug 13364, which was fixed by just updating bcel to 5.2
    private void getContent(Writer out, JavaClass c,
            List<String> defs, List<String> refs, List<String> full)
            throws IOException {
        String t;
        ConstantPool cp = c.getConstantPool();
        int[] v = new int[cp.getLength() + 1];
        out.write(linkPath(t = c.getSourceFileName()));
        defs.add(t);
        refs.add(t);
        out.write('\n');

        out.write("package ");
        out.write(linkDef(t = c.getPackageName()));
        defs.add(t);
        refs.add(t);

        out.write('\n');
        String aflg;
        out.write(aflg = Utility.accessToString(c.getAccessFlags(), true));
        if (aflg != null) {
            out.write(' ');
        }

        v[c.getClassNameIndex()] = 1;
        out.write(tagDef(t = c.getClassName()));
        defs.add(t);
        refs.add(t);
        out.write(" extends ");

        v[c.getSuperclassNameIndex()] = 1;
        out.write(linkDef(t = c.getSuperclassName()));
        refs.add(t);
        for (int i : c.getInterfaceIndices()) {
            v[i] = 1;
        }
        String ins[] = c.getInterfaceNames();
        if (ins != null && ins.length > 0) {
            out.write(" implements ");
            for (String in : ins) {
                out.write(linkDef(t = in));
                refs.add(t);
                out.write(' ');
            }
        }
        out.write(" {\n");

        for (Attribute a : c.getAttributes()) {
            if (a.getTag() == org.apache.bcel.Constants.ATTR_CODE) {
                for (Attribute ca : ((Code) a).getAttributes()) {
                    if (ca.getTag() == org.apache.bcel.Constants.ATTR_LOCAL_VARIABLE_TABLE) {
                        for (LocalVariable l : ((LocalVariableTable) ca).getLocalVariableTable()) {
                            printLocal(out, l, v, defs, refs);
                        }
                    }
                }
            } else if (a.getTag() == org.apache.bcel.Constants.ATTR_SOURCE_FILE) {
                v[a.getNameIndex()] = 1;
                break;
            }
        }

        for (org.apache.bcel.classfile.Field fld : c.getFields()) {
            out.write('\t');
            String aflgs;
            out.write(aflgs = Utility.accessToString(fld.getAccessFlags()));
            if (aflgs != null && aflgs.length() > 0) {
                out.write(' ');
            }
            out.write(Utility.signatureToString(fld.getSignature()));
            out.write(' ');
            out.write(tagDef(t = fld.getName()));
            defs.add(t);
            refs.add(t);
            out.write('\n');
            // @TODO show Attributes
        }

        for (org.apache.bcel.classfile.Method m : c.getMethods()) {
            out.write('\t');
            String aflgs;
            out.write(aflgs = Utility.accessToString(m.getAccessFlags()));
            if (aflgs != null && aflgs.length() > 0) {
                out.write(' ');
            }
            String sig = m.getSignature();
            out.write(Utility.methodSignatureReturnType(sig, false));
            out.write(' ');
            out.write(tagDef(t = m.getName()));
            defs.add(t);
            refs.add(t);
            out.write(" (");
            String[] args = Utility.methodSignatureArgumentTypes(sig, false);
            for (int i = 0; i < args.length; i++) {
                out.write(t = args[i]);
                int spi = t.indexOf(' ');
                if (spi > 0) {
                    refs.add(t.substring(0, spi));
                    defs.add(t.substring(spi + 1));
                }
                if (i < args.length - 1) {
                    out.write(", ");
                }
            }
            out.write(") ");
            ArrayList<LocalVariable[]> locals = new ArrayList<LocalVariable[]>();
            for (Attribute a : m.getAttributes()) {
                if (a.getTag() == org.apache.bcel.Constants.ATTR_EXCEPTIONS) {
                    for (int i : ((ExceptionTable) a).getExceptionIndexTable()) {
                        v[i] = 1;
                    }
                    String[] exs = ((ExceptionTable) a).getExceptionNames();
                    if (exs != null && exs.length > 0) {
                        out.write(" throws ");
                        for (String ex : exs) {
                            out.write(linkDef(ex));
                            refs.add(ex);
                            out.write(' ');
                        }
                    }
                } else if (a.getTag() == org.apache.bcel.Constants.ATTR_CODE) {
                    for (Attribute ca : ((Code) a).getAttributes()) {
                        if (ca.getTag() == org.apache.bcel.Constants.ATTR_LOCAL_VARIABLE_TABLE) {
                            locals.add(((LocalVariableTable) ca).getLocalVariableTable());
                        }
                    }
                }
            }
            out.write("\n");
            if (!locals.isEmpty()) {
                for (LocalVariable[] ls : locals) {
                    for (LocalVariable l : ls) {
                        printLocal(out, l, v, defs, refs);
                    }
                }
            }
        }
        out.write("}\n");
        for (int i = 0; i < v.length - 1; i++) {
            if (v[i] != 1) {
                Constant constant = cp.getConstant(i);
                if (constant != null) {
                    full.add(constantToString(constant, cp, v));
                }
            }
        }
    }

    private void printLocal(Writer out, LocalVariable l,
            int[] v, List<String> defs, List<String> refs) throws IOException {
        v[l.getIndex()] = 1;
        v[l.getNameIndex()] = 1;
        v[l.getSignatureIndex()] = 1;
        if (!"this".equals(l.getName())) {
            out.write("\t\t");
            out.write(Utility.signatureToString(l.getSignature()));
            out.write(' ');
            String t;
            out.write(t = l.getName());
            defs.add(t);
            refs.add(t);
            out.write('\n');
        }
    }

    public String constantToString(Constant c, ConstantPool cp, int[] v)
            throws ClassFormatException {
        String str;
        int i, j;
        byte tag = c.getTag();

        switch (tag) {
            case org.apache.bcel.Constants.CONSTANT_Class:
                i = ((ConstantClass) c).getNameIndex();
                v[i] = 1;
                Constant con = cp.getConstant(i, org.apache.bcel.Constants.CONSTANT_Utf8);
                str = Utility.compactClassName(((ConstantUtf8) con).getBytes(), false);
                break;

            case org.apache.bcel.Constants.CONSTANT_String:
                i = ((ConstantString) c).getStringIndex();
                v[i] = 1;
                Constant con2 = cp.getConstant(i, org.apache.bcel.Constants.CONSTANT_Utf8);
                str = ((ConstantUtf8) con2).getBytes();
                break;

            case org.apache.bcel.Constants.CONSTANT_Utf8:
                str = ((ConstantUtf8) c).toString();
                break;
            case org.apache.bcel.Constants.CONSTANT_Double:
                str = ((ConstantDouble) c).toString();
                break;
            case org.apache.bcel.Constants.CONSTANT_Float:
                str = ((ConstantFloat) c).toString();
                break;
            case org.apache.bcel.Constants.CONSTANT_Long:
                str = ((ConstantLong) c).toString();
                break;
            case org.apache.bcel.Constants.CONSTANT_Integer:
                str = ((ConstantInteger) c).toString();
                break;

            case org.apache.bcel.Constants.CONSTANT_NameAndType:
                i = ((ConstantNameAndType) c).getNameIndex();
                v[i] = 1;
                j = ((ConstantNameAndType) c).getSignatureIndex();
                v[j] = 1;
                String sig = constantToString(cp.getConstant(j), cp, v);
                if (sig.charAt(0) == '(') {
                    str = Utility.methodSignatureToString(sig,
                            constantToString(cp.getConstant(i), cp, v), " ");
                } else {
                    str = Utility.signatureToString(sig) + ' ' +
                            constantToString(cp.getConstant(i), cp, v);
                }
                //str = constantToString(cp.getConstant(i)) +' ' + sig;

                break;

            case org.apache.bcel.Constants.CONSTANT_InterfaceMethodref:
            case org.apache.bcel.Constants.CONSTANT_Methodref:
            case org.apache.bcel.Constants.CONSTANT_Fieldref:
                i = ((ConstantCP) c).getClassIndex();
                v[i] = 1;
                j = ((ConstantCP) c).getNameAndTypeIndex();
                v[j] = 1;
                str = (constantToString(cp.getConstant(i), cp, v) + ' ' +
                        constantToString(cp.getConstant(j), cp, v));
                break;

            default: // Never reached
                throw new ClassFormatException("Unknown constant type " + tag);
        }
        return str;
    }
}
