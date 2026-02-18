module iCacheSwPMU (
    input        clock,
    input        reset,
    input        resp,
    input        respHit,
    input [31:0] respAddr,
    input        req,
    input [31:0] reqAddr,
    input [15:0] id
);
  import "DPI-C" function void notify_cache_resp(
    input int unsigned addr,
    input byte unsigned is_hit,
    input shortint unsigned cache_id
  );
  import "DPI-C" function void notify_cache_req(
    input int unsigned addr,
    input shortint unsigned cache_id
  );

  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      if (resp) notify_cache_resp(respAddr, 8'(respHit), id);
      if (req) notify_cache_req(reqAddr, id);
    end
  end

endmodule
