module CacheDPICBox (
    input               clock,
    input               reset,
    input        [15:0] id,
    input               valid,
    input               flush,
    input        [31:0] addr,
    output logic [31:0] resp,
    output logic [31:0] latency
);
  import "DPI-C" function void axi_cache_flush(input shortint unsigned id);
  always_ff @(posedge clock) begin : fenceI
    if (flush) axi_cache_flush(id);
  end

  import "DPI-C" function int unsigned axi_read(
    input int unsigned raddr,
    output int unsigned rdata,
    input shortint unsigned id
  );

  logic [31:0] resp_wire;
  always_ff @(posedge clock) begin : SyncReadCache
    if (valid) begin
      latency <= axi_read(addr, resp_wire, id);
      resp <= resp_wire;
    end
  end

endmodule
