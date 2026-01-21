module DecodePMU (
    input        clock,
    input        reset,
    input        isNewInst,
    input        isFlush,
    input [31:0] pc,
    input [ 4:0] instOp
);
  import "DPI-C" function void notify_decode(
    int unsigned  pc,
    byte unsigned iop
  );

  import "DPI-C" function void notify_flush();

  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      if (isFlush) notify_flush();
      else if (isNewInst) notify_decode(pc, {3'h0, instOp});
    end
  end

endmodule
