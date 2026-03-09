module WrBackPMU (
    input        clock,
    input        reset,
    input        isNewInst,
    input [31:0] pc,
    input [31:0] inst,
    input [ 7:0] stallTp
);
  import "DPI-C" function void notify_commit(
    input int unsigned pc,
    input int unsigned inst,
    input byte unsigned stall_type
  );

  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      notify_commit(pc, inst, stallTp);
    end
  end

endmodule
