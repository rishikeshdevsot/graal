/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.hotspot.riscv64;

import static jdk.vm.ci.meta.Value.ILLEGAL;
import static org.graalvm.compiler.hotspot.HotSpotForeignCallLinkage.JUMP_ADDRESS;
import static org.graalvm.compiler.hotspot.HotSpotForeignCallLinkage.RegisterEffect.DESTROYS_ALL_CALLER_SAVE_REGISTERS;

import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.hotspot.HotSpotBackend;
import org.graalvm.compiler.hotspot.HotSpotForeignCallLinkageImpl;
import org.graalvm.compiler.hotspot.HotSpotGraalRuntimeProvider;
import org.graalvm.compiler.hotspot.meta.HotSpotHostForeignCallsProvider;
import org.graalvm.compiler.hotspot.meta.HotSpotProviders;
import org.graalvm.compiler.hotspot.word.HotSpotWordTypes;
import org.graalvm.compiler.options.OptionValues;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.hotspot.HotSpotCodeCacheProvider;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.Value;

public class RISCV64HotSpotForeignCallsProvider extends HotSpotHostForeignCallsProvider {

    private final Value[] nativeABICallerSaveRegisters;

    public RISCV64HotSpotForeignCallsProvider(HotSpotJVMCIRuntime jvmciRuntime, HotSpotGraalRuntimeProvider graalRuntime, MetaAccessProvider metaAccess,
                    HotSpotCodeCacheProvider codeCache, HotSpotWordTypes wordTypes, Value[] nativeABICallerSaveRegisters) {
        super(jvmciRuntime, graalRuntime, metaAccess, codeCache, wordTypes);
        this.nativeABICallerSaveRegisters = nativeABICallerSaveRegisters;
    }

    @Override
    public void initialize(HotSpotProviders providers, OptionValues options) {
        TargetDescription target = providers.getCodeCache().getTarget();
        PlatformKind word = target.arch.getWordKind();

        // The calling convention for the exception handler stub is (only?) defined in
        // TemplateInterpreterGenerator::generate_throw_exception()
        try {
            Class<?> riscv64 = Class.forName("jdk.vm.ci.riscv64.RISCV64");

            RegisterValue exception = ((Register) riscv64.getField("x5").get(null)).asValue(LIRKind.reference(word));
            RegisterValue exceptionPc = ((Register) riscv64.getField("x7").get(null)).asValue(LIRKind.value(word));

            CallingConvention exceptionCc = new CallingConvention(0, ILLEGAL, exception, exceptionPc);
            register(new HotSpotForeignCallLinkageImpl(HotSpotBackend.EXCEPTION_HANDLER, 0L, DESTROYS_ALL_CALLER_SAVE_REGISTERS, exceptionCc, null));
            register(new HotSpotForeignCallLinkageImpl(HotSpotBackend.EXCEPTION_HANDLER_IN_CALLER, JUMP_ADDRESS, DESTROYS_ALL_CALLER_SAVE_REGISTERS, exceptionCc,
                            null));

            super.initialize(providers, options);
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            throw GraalError.shouldNotReachHere("Running Native Image for RISC-V requires a JDK with JVMCI for RISC-V");
        }
    }

    @Override
    public Value[] getNativeABICallerSaveRegisters() {
        return nativeABICallerSaveRegisters;
    }
}
