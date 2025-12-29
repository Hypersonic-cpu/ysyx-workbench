module WrBackPMU(
    input        clock,
    input        reset,
    input        isNewInst,
    input [31:0] pc
);
  import "DPI-C" function void notify_commit(
    int unsigned  pc);

  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      if (isNewInst) notify_commit(pc);
    end
  end

endmodule
