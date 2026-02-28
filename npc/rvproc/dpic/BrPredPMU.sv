module BrPredPMU (
    input        clock,
    input        reset,
    input        valid,
    input        predTaken,
    input        actualTaken,
    input [31:0] predTarget,
    input [31:0] actualTarget,
    input        btbHit,
    input [31:0] brPC
);
  import "DPI-C" function void notify_bp_outcome(
    byte unsigned pred_taken,
    byte unsigned actual_taken,
    int unsigned  pred_target,
    int unsigned  actual_target,
    byte unsigned btb_hit,
    int unsigned  br_pc
  );

  always_ff @(posedge clock) begin
    if (!reset && valid)
      notify_bp_outcome(
        {7'h0, predTaken},
        {7'h0, actualTaken},
        predTarget,
        actualTarget,
        {7'h0, btbHit},
        brPC
      );
  end
endmodule
