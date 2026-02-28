module PMemBox (
    input         clock,
    input         reset,
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
    output [ 3:0] io_master_rid
);
  PMemReader mread (
      .clock            (clock),
      .reset            (reset),
      .io_master_arready(io_master_arready),
      .io_master_arvalid(io_master_arvalid),
      .io_master_araddr (io_master_araddr),
      .io_master_arid   (io_master_arid),
      .io_master_arlen  (io_master_arlen),
      .io_master_arsize (io_master_arsize),
      .io_master_arburst(io_master_arburst),
      .io_master_rready (io_master_rready),
      .io_master_rvalid (io_master_rvalid),
      .io_master_rresp  (io_master_rresp),
      .io_master_rdata  (io_master_rdata),
      .io_master_rlast  (io_master_rlast),
      .io_master_rid    (io_master_rid)
  );

  PMemWriter mwrite (
      .clock            (clock),
      .reset            (reset),
      .io_master_awready(io_master_awready),
      .io_master_awvalid(io_master_awvalid),
      .io_master_awaddr (io_master_awaddr),
      .io_master_awid   (io_master_awid),
      .io_master_awlen  (io_master_awlen),
      .io_master_awsize (io_master_awsize),
      .io_master_awburst(io_master_awburst),
      .io_master_wready (io_master_wready),
      .io_master_wvalid (io_master_wvalid),
      .io_master_wdata  (io_master_wdata),
      .io_master_wstrb  (io_master_wstrb),
      .io_master_wlast  (io_master_wlast),
      .io_master_bready (io_master_bready),
      .io_master_bvalid (io_master_bvalid),
      .io_master_bresp  (io_master_bresp),
      .io_master_bid    (io_master_bid)
  );
endmodule


module PMemReader (
    input         clock,
    input         reset,
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
    output [ 3:0] io_master_rid
);
  import "DPI-C" function int unsigned axi_read(
    input  int unsigned  raddr,
    output int unsigned  rdata,
    // input shortint unsigned id,
    input  byte unsigned outstanding
  );

  typedef enum logic [1:0] {
    IDLE  = 2'h0,
    RECV  = 2'h1,
    SERVE = 2'h2,
    HOLD  = 2'h3
  } state_t;
  state_t state;
  state_t next_state;
  reg [31:0] delay_remain;
  reg [3:0] rid_latch;
  reg [7:0] burst_remain;
  reg [7:0] burst_total;
  reg [31:0] raddr_latch;
  // wire [15:0] nxt_burst_remain;

  wire arfire = io_master_arvalid && io_master_arready;
  always_comb begin
    unique case (state)
      IDLE:  next_state = arfire ? RECV : IDLE;
      RECV:  next_state = SERVE;
      SERVE: next_state = (delay_remain == 1) ? HOLD : SERVE;
      HOLD:  next_state = io_master_rready ? (burst_remain == 0 ? IDLE : RECV) : HOLD;
    endcase
  end

  reg [31:0] rdata;
  always_ff @(posedge clock) begin
    if (reset) begin
      rdata <= 0;
      state <= IDLE;
      delay_remain <= 0;
      burst_remain <= 0;
      burst_total <= 0;
    end else begin
      state <= next_state;
      if (state == IDLE && arfire) begin
        rid_latch    <= io_master_arid;
        raddr_latch  <= io_master_araddr;
        burst_remain <= io_master_arlen;
        burst_total  <= io_master_arlen;
        raddr_latch  <= io_master_araddr;
        assert (io_master_arburst == 2'b01);  // INCR burst only
        // assert (io_master_arsize == 3'b010);  // 4-byte per beat
      end else if (state == RECV) begin
        delay_remain <= axi_read(raddr_latch, rdata, 8'(burst_remain == burst_total));
        raddr_latch  <= raddr_latch + 32'h4;
      end else if (state == SERVE) begin
        delay_remain <= delay_remain - 1;
      end else if (state == HOLD && next_state == RECV) begin
        burst_remain <= burst_remain - 1;
      end

      // if (state == RECV || state == SERVE)
      //   $strobe("> Reader State %x counter %d req %d", state, delay_remain, io_master_arvalid);
    end
  end

  no_count_at_idle :
  assert property (@(posedge clock) (delay_remain != 0) |-> (state == SERVE));

  assign io_master_rid     = rid_latch;
  assign io_master_rvalid  = state == HOLD;
  assign io_master_rdata   = {32{io_master_rvalid}} & rdata;
  assign io_master_arready = state == IDLE;
  assign io_master_rresp   = 2'b00;
  assign io_master_rlast   = burst_remain == 0;
endmodule


module PMemWriter (
    input         clock,
    input         reset,
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
    output [ 3:0] io_master_bid
);
  import "DPI-C" function int unsigned axi_write(
    input int unsigned  waddr,
    input int unsigned  wdata,
    input byte unsigned wmask,
    input byte unsigned outstanding
  );

  typedef enum logic [2:0] {
    IDLE  = 3'h0,
    WDATA = 3'h1,
    RECV  = 3'h2,
    SERVE = 3'h3,
    HOLD  = 3'h4
  } state_t;
  state_t state;
  state_t next_state;
  reg [31:0] delay_remain;
  reg [3:0] bid_latch;
  reg [31:0] waddr_latch;
  reg [31:0] wdata_latch;
  reg [3:0] wstrb_latch;
  reg wlast_latch;

  always_comb begin
    unique case (state)
      IDLE: begin
        if (io_master_awvalid && io_master_wvalid)
          next_state = RECV;
        else if (io_master_awvalid)
          next_state = WDATA;
        else
          next_state = IDLE;
      end
      WDATA: next_state = io_master_wvalid ? RECV : WDATA;
      RECV:  next_state = SERVE;
      SERVE: next_state = (delay_remain == 1)
               ? (wlast_latch ? HOLD : WDATA) : SERVE;
      HOLD:  next_state = io_master_bready ? IDLE : HOLD;
      default: next_state = IDLE;
    endcase
  end

  always_ff @(posedge clock) begin
    if (reset) begin
      state <= IDLE;
      delay_remain <= 0;
      wlast_latch <= 0;
    end else begin
      state <= next_state;
      if (state == IDLE && io_master_awvalid) begin
        waddr_latch <= io_master_awaddr;
        bid_latch   <= io_master_awid;
        assert (io_master_awburst == 2'b01);
      end
      if ((state == IDLE || state == WDATA) && io_master_wvalid) begin
        wdata_latch <= io_master_wdata;
        wstrb_latch <= io_master_wstrb;
        wlast_latch <= io_master_wlast;
      end
      if (state == RECV) begin
        delay_remain <= axi_write(
          waddr_latch, wdata_latch,
          8'(wstrb_latch), 8'(wlast_latch)
        );
        waddr_latch <= waddr_latch + 32'h4;
      end
      if (state == SERVE)
        delay_remain <= delay_remain - 1;
    end
  end

  no_count_at_idle :
  assert property (@(posedge clock) (delay_remain != 0) |-> (state == SERVE));

  assign io_master_bid     = bid_latch;
  assign io_master_bvalid  = state == HOLD;
  assign io_master_awready = state == IDLE;
  assign io_master_wready  = state == IDLE || state == WDATA;
  assign io_master_bresp   = 2'b00;
endmodule
