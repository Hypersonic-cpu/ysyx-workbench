module WrBackPMU (
    input        clock,
    input        reset,
    input        isNewInst,
    input [31:0] pc,
    input [31:0] inst
);
  import "DPI-C" function void notify_commit(
    input int unsigned pc,
    input int unsigned inst
  );

  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      if (isNewInst) notify_commit(pc, inst);
    end
  end

endmodule
