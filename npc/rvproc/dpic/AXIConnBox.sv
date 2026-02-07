
module AXIConnBox (
    input clock,
    input reset,

    output        io_master_awready,
    input         io_master_awvalid,
    input  [31:0] io_master_awaddr,
    input  [ 3:0] io_master_awid,
    input  [ 7:0] io_master_awlen,
    input  [ 2:0] io_master_awsize,
    input  [ 1:0] io_master_awburst,
    output        io_master_wready,
    input         io_master_wvalid,
    input  [31:0] io_master_wdata,
    input  [ 3:0] io_master_wstrb,
    input         io_master_wlast,
    input         io_master_bready,
    output        io_master_bvalid,
    output [ 1:0] io_master_bresp,
    output [ 3:0] io_master_bid,

    output        io_master_arready,
    input         io_master_arvalid,
    input  [31:0] io_master_araddr,
    input  [ 3:0] io_master_arid,
    input  [ 7:0] io_master_arlen,
    input  [ 2:0] io_master_arsize,
    input  [ 1:0] io_master_arburst,
    input         io_master_rready,
    output        io_master_rvalid,
    output [ 1:0] io_master_rresp,
    output [31:0] io_master_rdata,
    output        io_master_rlast,
    output [ 3:0] io_master_rid,
    input         io_flush_valid,
    input  [15:0] io_flush_id
);
  logic [15:0] device_id = io_flush_id;

  /**
   *         *-----------*
   * ________| ^  ^      |________
   *           |  |
   * valid called  ready updated
   */

  // Request
  import "DPI-C" function void axi_read_req(
    input bit [31:0] araddr,
    input bit [15:0] arid,
    input bit [15:0] arlen,
    input bit [15:0] arsize,
    input bit [15:0] arburst
  );

  import "DPI-C" function void axi_write_req(
    input bit [31:0] awaddr,
    input bit [15:0] awid,
    input bit [15:0] awlen,
    input bit [15:0] awsize,
    input bit [15:0] awburst,
    input bit [31:0] wdata,
    input bit [ 7:0] wstrb,
    input bit [ 7:0] wlast
  );

  import "DPI-C" function void axi_cache_flush(input bit [15:0] sim_id);

  // Response Prober
  // Assume host is always ready
  import "DPI-C" function void axi_read_resp(
    output bit [ 7:0] rvalid,
    output bit [ 7:0] rresp,
    output bit [31:0] rdata,
    output bit [ 7:0] rlast,
    output bit [15:0] rid,
    input  bit [15:0] devid
  );

  import "DPI-C" function void axi_write_resp(
    output bit [ 7:0] bvalid,
    output bit [ 7:0] bresp,
    output bit [15:0] rid,
    input  bit [15:0] devid
  );

  import "DPI-C" function void axi_device_ready(
    output bit [ 7:0] r_port_ready,
    output bit [ 7:0] w_port_ready,
    input  bit [15:0] devid
  );

  logic [ 7:0] c_rvalid;
  logic [ 7:0] c_rresp;
  logic [31:0] c_rdata;
  logic [ 7:0] c_rlast;
  logic [15:0] c_rid;

  logic [ 7:0] c_bvalid;
  logic [ 7:0] c_bresp;
  logic [15:0] c_bid;

  logic [ 7:0] c_r_ready;
  logic [ 7:0] c_w_ready;

  logic        ar_fire = io_master_arvalid && io_master_arready;
  logic        aw_fire = io_master_awvalid && io_master_awready;
  logic        w_fire = io_master_wvalid && io_master_wready;
  always_ff @(posedge clock) begin : Everyting
    if (reset) begin
      //
    end else begin
      if (ar_fire) begin
        assert (io_master_arlen == 0);  // "Only support single beat read"
        axi_read_req(
            /* 31:0 */ 32'(io_master_araddr),
            /* 15:0 */ 16'(io_master_arid),
            /* 15:0 */ 16'(io_master_arlen),
            /* 15:0 */ 16'(io_master_arsize),
            /* 15:0 */ 16'(io_master_arburst));
      end else if (aw_fire) begin
        assert (w_fire);  // "AW and W valid is restricted.
        assert (io_master_awlen == 0);  // "Only support single beat write"
        // Decoupled w and aw & burst read/write is not supported so far.
        axi_write_req(
            /* 31:0 */ 32'(io_master_awaddr),
            /* 15:0 */ 16'(io_master_awid),
            /* 15:0 */ 16'(io_master_awlen),
            /* 15:0 */ 16'(io_master_awsize),
            /* 15:0 */ 16'(io_master_awburst),
            /* 31:0 */ 32'(io_master_wdata),
            /*  7:0 */ 8'(io_master_wstrb),
            /*  7:0 */ 8'(io_master_wlast));
      end
      if (io_flush_valid) begin
        axi_cache_flush(device_id);
      end
    end
    // Response probing, called only once per cycle. Will clear CXX-side valid bit.
    axi_read_resp(c_rvalid, c_rresp, c_rdata, c_rlast, c_rid, device_id);
    axi_write_resp(c_bvalid, c_bresp, c_bid, device_id);
    axi_device_ready(c_r_ready, c_w_ready, device_id);
  end

  assign io_master_bvalid = c_bvalid[0];
  assign io_master_bresp  = c_bresp[1:0];
  assign io_master_bid    = c_bid[3:0];

  assign io_master_rvalid = c_rvalid[0];
  assign io_master_rresp  = c_rresp[1:0];
  assign io_master_rdata  = c_rdata[31:0];
  assign io_master_rlast  = c_rlast[0];
  assign io_master_rid    = c_rid[3:0];

  assign io_master_arready = c_r_ready[0];
  assign io_master_awready = c_w_ready[0];
  assign io_master_wready = c_w_ready[0];

  // Assume host is always ready
  assert property (@(posedge clock) io_master_rvalid |-> io_master_rready);
  assert property (@(posedge clock) io_master_bvalid |-> io_master_bready);
endmodule
