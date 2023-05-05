/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2020, Red Hat Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.image;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.HashMap;

import com.oracle.objectfile.debuginfo.DebugInfoProvider;
import static com.oracle.svm.core.util.VMError.unimplemented;

import com.oracle.svm.hosted.meta.HostedArrayClass;
import com.oracle.svm.hosted.meta.HostedClass;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedInstanceClass;
import com.oracle.svm.hosted.meta.HostedPrimitiveType;
import com.oracle.svm.hosted.meta.HostedType;

import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import org.graalvm.compiler.debug.DebugContext;

public class LLVMDebugInfoProvider {
    public static NativeImageHeap heap;
    NativeImageDebugInfoProvider dbgInfoHelper;
    public static HashMap<String, HostedType> typeMap = new HashMap<String, HostedType>();
    public DebugContext debugContext;

    public LLVMDebugInfoProvider() {
    }

   public static void initializeHeap(NativeImageHeap heapArg) {
       heap = heapArg;
   }

   public NativeImageDebugInfoProvider getHelper() {
        return dbgInfoHelper;
   }

   public static void generateTypeMap() {
        Stream<HostedType> typeStream = heap.getUniverse().getTypes().stream();
        typeStream.forEach(hostedType -> typeMap.put(hostedType.getName(), hostedType));
    }

    public class LLVMDebugFieldInfo extends LLVMDebugFileInfo {
        HostedField field;

        LLVMDebugFieldInfo(HostedField field) {
            super(field);
            this.field = field;
        }

        public HostedField getField() {
            return field;
        }

        public String name() {
            return field.getName();
        }

        public ResolvedJavaType valueType() {
            return DebugInfoProviderHelper.getOriginal(field.getType());
        }

        public int offset() {
            return field.getOffset();
        }

        public int size() {
            return DebugInfoProviderHelper.getObjectLayout().sizeInBytes(field.getType().getStorageKind());
        }

        public boolean isEnumerator() {
            return ((field.getType() instanceof HostedClass) && (((HostedClass) (field.getType())).isEnum()));
        }
    }

    public class LLVMDebugPrimitiveTypeInfo extends LLVMDebugTypeInfo {
        private final HostedPrimitiveType primitiveType;
        public LLVMDebugPrimitiveTypeInfo(HostedPrimitiveType primitiveType) {
            super(primitiveType);
            this.primitiveType = primitiveType;

        }

        public int bitCount() {
            return DebugInfoProviderHelper.getPrimitiveBitCount(primitiveType);
        }

        public int flags() {
            return DebugInfoProviderHelper.getPrimitiveFlags(primitiveType);
        }

        public byte computeEncoding() {
            return DebugInfoProviderHelper.computeEncoding(flags(), bitCount());
        }
    }


    public class LLVMDebugEnumTypeInfo extends LLVMDebugInstanceTypeInfo {
        public LLVMDebugEnumTypeInfo(HostedInstanceClass enumClass) {
            super(enumClass);
        }
    }


    public class LLVMDebugInstanceTypeInfo extends LLVMDebugTypeInfo {

        public LLVMDebugInstanceTypeInfo(HostedType hostedType) {
            super(hostedType);
        }

        public Stream<LLVMDebugFieldInfo> fieldInfoProvider() {
            Stream<LLVMDebugFieldInfo> instanceFieldsStream =
                    Arrays.stream(hostedType.getInstanceFields(false)).map(this::createDebugFieldInfo);
            if (hostedType instanceof HostedInstanceClass && hostedType.getStaticFields().length > 0) {
                Stream<LLVMDebugFieldInfo> staticFieldsStream = Arrays.stream(hostedType.getStaticFields()).map(this::createDebugStaticFieldInfo);
                return Stream.concat(instanceFieldsStream, staticFieldsStream);
            } else {
                return instanceFieldsStream;
            }
        }

        public Stream<LLVMDebugFieldInfo> staticFieldInfoProvider() {
            if (hostedType instanceof HostedInstanceClass && hostedType.getStaticFields().length > 0) {
                Stream<LLVMDebugFieldInfo> staticFieldsStream =
                        Arrays.stream(hostedType.getStaticFields()).map(this::createDebugStaticFieldInfo);
                return staticFieldsStream;
            } else {
                return null;
            }
        }

        private LLVMDebugFieldInfo createDebugFieldInfo(HostedField field) {
            return new LLVMDebugFieldInfo(field);
        }

        private LLVMDebugFieldInfo createDebugStaticFieldInfo(ResolvedJavaField field) {
            return new LLVMDebugFieldInfo((HostedField) field);
        }

        public ResolvedJavaType superClass() {
            return hostedType.getSuperclass();
        }
    }

    public class LLVMDebugArrayTypeInfo extends LLVMDebugTypeInfo {
        private HostedArrayClass arrayClass;

        public LLVMDebugArrayTypeInfo(HostedArrayClass arrayClass) {
            super(arrayClass);
            this.arrayClass = arrayClass;
        }

        public int arrayDimension() {
            return arrayClass.getArrayDimension();
        }

        public HostedType baseType() {
            return arrayClass.getBaseType();
        }

        public int baseSize() {
            return size();
        }
    }

    public class LLVMDebugTypeInfo extends LLVMDebugFileInfo {
        HostedType hostedType;

        public LLVMDebugTypeInfo(HostedType hostedType) {
            super(hostedType);
            this.hostedType = hostedType;
        }

        public ResolvedJavaType idType() {
            // always use the original type for establishing identity
            return DebugInfoProviderHelper.getOriginal(hostedType);
        }

        public String typeName() {
            return DebugInfoProviderHelper.toJavaName(hostedType);
        }

        public int size() {
            return DebugInfoProviderHelper.typeSize(hostedType);
        }
    }
    public class LLVMDebugFileInfo {
        Path fullFilePath;

        LLVMDebugFileInfo(ResolvedJavaMethod method, DebugContext debugContext) {
            fullFilePath = DebugInfoProviderHelper.getFullFilePathFromMethod(method, debugContext);
        }

        LLVMDebugFileInfo(HostedType hostedType) {
            fullFilePath = DebugInfoProviderHelper.getFullFilePathFromType(hostedType, debugContext);
        }

        LLVMDebugFileInfo(HostedField hostedField) {
            fullFilePath = DebugInfoProviderHelper.getFullFilePathFromField(hostedField, debugContext);
        }

        public String fileName() {
            return DebugInfoProviderHelper.getFileName(fullFilePath);
        }

        public Path filePath() {
            return DebugInfoProviderHelper.getFilePath(fullFilePath);
        }

        public Path cachePath() {
            return null;
        }
}

   public class LLVMLocationInfo extends LLVMDebugFileInfo {
        private int bci;
        private ResolvedJavaMethod method;

        public LLVMLocationInfo(ResolvedJavaMethod method, int bci, DebugContext debugContext) {
            super(method, debugContext);
            this.bci = bci;
            this.method = method;
        }

       public int line() {
            return DebugInfoProviderHelper.getLineNumber(this.method, this.bci);
       }

       public String name() {
           return DebugInfoProviderHelper.getMethodName(method);
       }
   }
}
