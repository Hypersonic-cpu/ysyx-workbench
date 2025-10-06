// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design implementation internals
// See Vexample.h for the primary calling header

#include "verilated.h"

#include "Vexample__Syms.h"
#include "Vexample___024root.h"

#ifdef VL_DEBUG
VL_ATTR_COLD void Vexample___024root___dump_triggers__ico(Vexample___024root* vlSelf);
#endif  // VL_DEBUG

void Vexample___024root___eval_triggers__ico(Vexample___024root* vlSelf) {
    if (false && vlSelf) {}  // Prevent unused
    Vexample__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vexample___024root___eval_triggers__ico\n"); );
    // Body
    vlSelf->__VicoTriggered.at(0U) = (0U == vlSelf->__VicoIterCount);
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        Vexample___024root___dump_triggers__ico(vlSelf);
    }
#endif
}

VL_INLINE_OPT void Vexample___024root___ico_sequent__TOP__0(Vexample___024root* vlSelf) {
    if (false && vlSelf) {}  // Prevent unused
    Vexample__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vexample___024root___ico_sequent__TOP__0\n"); );
    // Body
    if (((IData)(vlSelf->a) ^ (IData)(vlSelf->example__DOT____Vtogcov__a))) {
        vlSymsp->__Vcoverage[0].fetch_add(1, std::memory_order_relaxed);
        vlSelf->example__DOT____Vtogcov__a = vlSelf->a;
    }
    if (((IData)(vlSelf->b) ^ (IData)(vlSelf->example__DOT____Vtogcov__b))) {
        vlSymsp->__Vcoverage[1].fetch_add(1, std::memory_order_relaxed);
        vlSelf->example__DOT____Vtogcov__b = vlSelf->b;
    }
    vlSelf->f = ((IData)(vlSelf->a) ^ (IData)(vlSelf->b));
    if (((IData)(vlSelf->f) ^ (IData)(vlSelf->example__DOT____Vtogcov__f))) {
        vlSymsp->__Vcoverage[2].fetch_add(1, std::memory_order_relaxed);
        vlSelf->example__DOT____Vtogcov__f = vlSelf->f;
    }
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vexample___024root___dump_triggers__act(Vexample___024root* vlSelf);
#endif  // VL_DEBUG

void Vexample___024root___eval_triggers__act(Vexample___024root* vlSelf) {
    if (false && vlSelf) {}  // Prevent unused
    Vexample__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vexample___024root___eval_triggers__act\n"); );
    // Body
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        Vexample___024root___dump_triggers__act(vlSelf);
    }
#endif
}
