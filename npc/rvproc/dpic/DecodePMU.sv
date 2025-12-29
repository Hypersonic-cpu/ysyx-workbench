module DecodePMU (
    input        clock,
    input        reset,
    input        isNewInst,
    input [31:0] pc,
    input [ 2:0] instType,
    input [ 4:0] instOp
    // input        isIdle,
    // input [ 2:0] idleCause
);
  import "DPI-C" function void notify_decode(
    int unsigned  pc,
    byte unsigned itype,
    byte unsigned iop
  );

  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      if (isNewInst) notify_decode(pc, {5'h0, instType}, {3'h0, instOp});
    end
  end

endmodule
