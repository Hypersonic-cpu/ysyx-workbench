// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design implementation internals
// See Vexample.h for the primary calling header

#include "verilated.h"

#include "Vexample__Syms.h"
#include "Vexample___024root.h"

#ifdef VL_DEBUG
VL_ATTR_COLD void Vexample___024root___dump_triggers__stl(Vexample___024root* vlSelf);
#endif  // VL_DEBUG

VL_ATTR_COLD void Vexample___024root___eval_triggers__stl(Vexample___024root* vlSelf) {
    if (false && vlSelf) {}  // Prevent unused
    Vexample__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vexample___024root___eval_triggers__stl\n"); );
    // Body
    vlSelf->__VstlTriggered.at(0U) = (0U == vlSelf->__VstlIterCount);
#ifdef VL_DEBUG
    if (VL_UNLIKELY(vlSymsp->_vm_contextp__->debug())) {
        Vexample___024root___dump_triggers__stl(vlSelf);
    }
#endif
}

VL_ATTR_COLD void Vexample___024root___configure_coverage(Vexample___024root* vlSelf, bool first) {
    if (false && vlSelf) {}  // Prevent unused
    Vexample__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vexample___024root___configure_coverage\n"); );
    // Body
    if (false && first) {}  // Prevent unused
    vlSelf->__vlCoverInsert(&(vlSymsp->__Vcoverage[0]), first, "vsrc/example.v", 2, 9, ".example", "v_toggle/example", "a", "");
    vlSelf->__vlCoverInsert(&(vlSymsp->__Vcoverage[1]), first, "vsrc/example.v", 3, 9, ".example", "v_toggle/example", "b", "");
    vlSelf->__vlCoverInsert(&(vlSymsp->__Vcoverage[2]), first, "vsrc/example.v", 4, 10, ".example", "v_toggle/example", "f", "");
}
