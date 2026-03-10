module PfSwPMU (
    input        clock,
    input        reset,
    input        pfIssued,
    input        pfHitC2,
    input        pfUseful,
    input [31:0] pfAddr
);
  import "DPI-C" function void notify_pf_event(
    input byte unsigned event_type,
    input int unsigned  addr
  );

  // event_type: 0=issued, 1=hitC2, 2=useful
  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      if (pfIssued) notify_pf_event(8'd0, pfAddr);
      if (pfHitC2)  notify_pf_event(8'd1, pfAddr);
      if (pfUseful) notify_pf_event(8'd2, pfAddr);
    end
  end

endmodule
