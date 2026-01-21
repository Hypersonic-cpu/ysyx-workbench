module FetchPMU (
    input        clock,
    input        reset,
    input        trigFetch,
    input        trigRecvd,
    input [31:0] pcFetch,
    input [31:0] pcRecvd,
    input [31:0] inst
    // input        isIdle,
    // input [ 2:0] idleCause
);
  // Used for Delta(PC) statistics.
  import "DPI-C" function void
    notify_recvd(int unsigned newPC, int unsigned inst);
  import "DPI-C" function void
    notify_fetch(int unsigned newPC);

  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      // $display("fetch ? %d issue ? %d", trigFetch, trigRecvd);
      if (trigFetch) notify_fetch(pcFetch);
      if (trigRecvd) notify_recvd(pcRecvd, inst);
    end
  end

endmodule
