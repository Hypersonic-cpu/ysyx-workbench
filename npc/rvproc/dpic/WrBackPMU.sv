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
      // isNewInst=true → commit (stalltp=0)
      // isNewInst=false, stallTp!=0 → genuine stall
      // isNewInst=false, stallTp==0 → bubble (encode as IfuStall=1)
      notify_commit(pc, inst,
        isNewInst ? 8'h0 : (stallTp == 8'h0 ? 8'h1 : stallTp));
    end
  end

endmodule
