module FetchPMU (
    input        clock,
    input        reset,
    input        trigFetch,
    input        trigIssue,
    input [31:0] pc,
    input [31:0] inst
    // input        isIdle,
    // input [ 2:0] idleCause
);
  // Used for Delta(PC) statistics.
  import "DPI-C" function void
    notify_issue(int unsigned newPC, int unsigned inst);
  import "DPI-C" function void
    notify_fetch(int unsigned newPC);

  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      // if (trigFetch) notify_fetch(pc);
      // if (trigIssue) notify_issue(pc, inst);
      // assert(!(trigFetch && trigIssue));
    end
  end

endmodule
