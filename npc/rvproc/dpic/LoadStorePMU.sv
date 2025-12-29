module LoadStorePMU (
    input        clock,
    input        reset,
    input        trigReq,
    input        trigResp,
    input [31:0] addr
);
  import "DPI-C" function void
    notify_ls_req(int unsigned addr);
  import "DPI-C" function void
    notify_ls_resp(int unsigned addr);

  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      if (trigReq ) notify_ls_req(addr);
      if (trigResp) notify_ls_resp(addr);
      assert(!(trigResp && trigReq));
    end
  end

endmodule
