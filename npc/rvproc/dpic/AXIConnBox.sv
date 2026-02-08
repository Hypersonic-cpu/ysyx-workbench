
module AXIConnBox (
    input clock,
    input reset,

    output logic        io_master_awready,
    input  logic        io_master_awvalid,
    input  logic [31:0] io_master_awaddr,
    input  logic [ 3:0] io_master_awid,
    input  logic [ 7:0] io_master_awlen,
    input  logic [ 2:0] io_master_awsize,
    input  logic [ 1:0] io_master_awburst,
    output logic        io_master_wready,
    input  logic        io_master_wvalid,
    input  logic [31:0] io_master_wdata,
    input  logic [ 3:0] io_master_wstrb,
    input  logic        io_master_wlast,
    input  logic        io_master_bready,
    output logic        io_master_bvalid,
    output logic [ 1:0] io_master_bresp,
    output logic [ 3:0] io_master_bid,

    output logic        io_master_arready,
    input  logic        io_master_arvalid,
    input  logic [31:0] io_master_araddr,
    input  logic [ 3:0] io_master_arid,
    input  logic [ 7:0] io_master_arlen,
    input  logic [ 2:0] io_master_arsize,
    input  logic [ 1:0] io_master_arburst,
    input  logic        io_master_rready,
    output logic        io_master_rvalid,
    output logic [ 1:0] io_master_rresp,
    output logic [31:0] io_master_rdata,
    output logic        io_master_rlast,
    output logic [ 3:0] io_master_rid,
    input  logic        io_flush_valid,
    input  logic [15:0] io_flush_id
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
    input int unsigned araddr,
    input shortint unsigned arid,
    input shortint unsigned arlen,
    input shortint unsigned arsize,
    input shortint unsigned arburst
  );

  import "DPI-C" function void axi_write_req(
    input int unsigned awaddr,
    input shortint unsigned awid,
    input shortint unsigned awlen,
    input shortint unsigned awsize,
    input shortint unsigned awburst,
    input int unsigned wdata,
    input byte unsigned wstrb,
    input byte unsigned wlast
  );

  import "DPI-C" function void axi_cache_flush(input shortint unsigned sim_id);

  // Response Prober
  // Assume host is always ready
  import "DPI-C" function void axi_read_resp(
    output byte unsigned rvalid,
    output byte unsigned rresp,
    output int unsigned rdata,
    output byte unsigned rlast,
    output shortint unsigned rid,
    input shortint unsigned devid,
    input byte unsigned devready
  );

  import "DPI-C" function void axi_write_resp(
    output byte unsigned bvalid,
    output byte unsigned bresp,
    output shortint unsigned rid,
    input shortint unsigned devid,
    input byte unsigned devready
  );

  import "DPI-C" function void axi_device_ready(
    output byte unsigned r_port_ready,
    output byte unsigned w_port_ready,
    input shortint unsigned devid
  );

  reg [7:0] c_rvalid;
  reg [7:0] c_rresp;
  reg [31:0] c_rdata;
  reg [7:0] c_rlast;
  reg [15:0] c_rid;
  reg [7:0] c_bvalid;
  reg [7:0] c_bresp;
  reg [15:0] c_bid;
  reg [7:0] c_ar_ready;
  reg [7:0] c_aw_ready;

  logic ar_fire = io_master_arvalid && io_master_arready;
  logic aw_fire = io_master_awvalid && io_master_awready;
  logic w_fire = io_master_wvalid && io_master_wready;
  //
  // reg prb_arready;
  // reg prb_awready;
  // reg prb_wready;

  event posedge_updated;

  always_ff @(posedge clock) begin : Everyting
    // if (io_master_arid == 0) begin
    //   $display("++ DEVICE DISP ID = %d AR_FIRE %d %d ++", device_id, io_master_arvalid,
    //            io_master_arready);
    //   $strobe("++ DEVICE STRB ID = %d AR_FIRE %d %d ++", device_id, io_master_arvalid,
    //           io_master_arready);
    // end

    if (reset) begin
    end else begin
      // Forced blocking assignment to avoid `ready` being modified.

      // Response probing, called only once per cycle. Will clear CXX-side valid bit.
      // Asking for CURRENT CYCLE status.
      // Transaction caused valid clearing event only influences the next cycle

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
    // prb_arready <= c_r_ready[0];
    // prb_awready <= c_w_ready[0];
    // prb_wready  <= c_w_ready[0];

    ->posedge_updated;
  end

  always @(posedge clock) begin
    axi_device_ready(c_ar_ready, c_aw_ready, device_id);
    axi_read_resp(c_rvalid, c_rresp, c_rdata, c_rlast, c_rid, device_id, 8'(io_master_arready));
    axi_write_resp(c_bvalid, c_bresp, c_bid, device_id, 8'(io_master_awready));

    io_master_bvalid <= c_bvalid[0];
    io_master_bresp  <= c_bresp[1:0];
    io_master_bid    <= c_bid[3:0];
    io_master_rvalid <= c_rvalid[0];
    io_master_rresp  <= c_rresp[1:0];
    io_master_rdata  <= c_rdata[31:0];
    io_master_rlast  <= c_rlast[0];
    io_master_rid    <= c_rid[3:0];
    io_master_arready <= c_ar_ready[0];
    io_master_awready <= c_aw_ready[0];
    io_master_wready  <= c_aw_ready[0];
  end
  //
  // assign io_master_bvalid = c_bvalid[0];
  // assign io_master_bresp  = c_bresp[1:0];
  // assign io_master_bid    = c_bid[3:0];
  // assign io_master_rvalid = c_rvalid[0];
  // assign io_master_rresp  = c_rresp[1:0];
  // assign io_master_rdata  = c_rdata[31:0];
  // assign io_master_rlast  = c_rlast[0];
  // assign io_master_rid    = c_rid[3:0];
  // assign io_master_arready = c_r_ready[0];
  // assign io_master_awready = c_w_ready[0];
  // assign io_master_wready  = c_w_ready[0];

  // Assume host is always ready
  assert property (@(posedge clock) io_master_rvalid |-> io_master_rready);
  assert property (@(posedge clock) io_master_bvalid |-> io_master_bready);
endmodule
