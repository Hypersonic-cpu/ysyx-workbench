module PMemBox (
    input         clock,
    input         reset,
    output        io_master_awready,
    input         io_master_awvalid,
    input  [31:0] io_master_awaddr,
    // input  [3:0]  io_master_awid    ,
    // input  [7:0]  io_master_awlen   ,
    // input  [2:0]  io_master_awsize  ,
    // input  [1:0]  io_master_awburst ,
    output        io_master_wready,
    input         io_master_wvalid,
    input  [31:0] io_master_wdata,
    input  [ 3:0] io_master_wstrb,
    // input         io_master_wlast   ,
    input         io_master_bready,
    output        io_master_bvalid,
    output [ 1:0] io_master_bresp,
    // output [3:0]  io_master_bid     ,
    output        io_master_arready,
    input         io_master_arvalid,
    input  [31:0] io_master_araddr,
    // input  [3:0]  io_master_arid    ,
    // input  [7:0]  io_master_arlen   ,
    // input  [2:0]  io_master_arsize  ,
    // input  [1:0]  io_master_arburst ,
    input         io_master_rready,
    output        io_master_rvalid,
    output [ 1:0] io_master_rresp,
    output [31:0] io_master_rdata     // ,
    // output        io_master_rlast   ,
    // output [3:0]  io_master_rid     ,

    // input         io_slave_awready,
    // output        io_slave_awvalid,
    // output [31:0] io_slave_awaddr,
    // // output [3:0]   io_slave_awid    ,
    // // output [7:0]   io_slave_awlen   ,
    // // output [2:0]   io_slave_awsize  ,
    // // output [1:0]   io_slave_awburst ,
    // input         io_slave_wready,
    // output        io_slave_wvalid,
    // output [31:0] io_slave_wdata,
    // output [ 3:0] io_slave_wstrb,
    // // output         io_slave_wlast   ,
    // output        io_slave_bready,
    // input         io_slave_bvalid,
    // input  [ 1:0] io_slave_bresp,
    // // input  [3:0]   io_slave_bid     ,
    // input         io_slave_arready,
    // output        io_slave_arvalid,
    // output [31:0] io_slave_araddr,
    // // output [3:0]   io_slave_arid    ,
    // // output [7:0]   io_slave_arlen   ,
    // // output [2:0]   io_slave_arsize  ,
    // // output [1:0]   io_slave_arburst ,
    // output        io_slave_rready,
    // input         io_slave_rvalid,
    // input  [ 1:0] io_slave_rresp,
    // input  [31:0] io_slave_rdata     // ,
    // // input          io_slave_rlast   ,
    // // input  [3:0]   io_slave_rid
);

  import "DPI-C" function void pmem_init();
  initial begin
    pmem_init();
  end

  PMemReader mread (
      .clock            (clock),
      .reset            (reset),
      .io_master_arready(io_master_arready),
      .io_master_arvalid(io_master_arvalid),
      .io_master_araddr (io_master_araddr),
      // .io_master_arid   (io_master_arid),
      // .io_master_arlen  (io_master_arlen),
      // .io_master_arsize (io_master_arsize),
      // .io_master_arburst(io_master_arburst),
      .io_master_rready (io_master_rready),
      .io_master_rvalid (io_master_rvalid),
      .io_master_rresp  (io_master_rresp),
      .io_master_rdata  (io_master_rdata)     // ,
      // .io_master_rlast  (io_master_rlast),
      // .io_master_rid    (io_master_rid)
  );

  PMemWriter mwrite (
      .clock            (clock),
      .reset            (reset),
      .io_master_awready(io_master_awready),
      .io_master_awvalid(io_master_awvalid),
      .io_master_awaddr (io_master_awaddr),
      // .io_master_awid   (io_master_awid),
      // .io_master_awlen  (io_master_awlen),
      // .io_master_awsize (io_master_awsize),
      // .io_master_awburst(io_master_awburst),
      .io_master_wready (io_master_wready),
      .io_master_wvalid (io_master_wvalid),
      .io_master_wdata  (io_master_wdata),
      .io_master_wstrb  (io_master_wstrb),
      // .io_master_wlast  (io_master_wlast),
      .io_master_bready (io_master_bready),
      .io_master_bvalid (io_master_bvalid),
      .io_master_bresp  (io_master_bresp)     // ,
      // .io_master_bid    (io_master_bid),
  );

  // assign io_slave_awvalid = 0;
  // assign io_slave_awaddr  = 0;
  // assign io_slave_awid    = 0;
  // assign io_slave_awlen   = 0;
  // assign io_slave_awsize  = 0;
  // assign io_slave_awburst = 0;
  // assign io_slave_wvalid  = 0;
  // assign io_slave_wdata   = 0;
  // assign io_slave_wstrb   = 0;
  // assign io_slave_wlast   = 0;
  // assign io_slave_bready  = 0;
  // assign io_slave_arvalid = 0;
  // assign io_slave_araddr  = 0;
  // assign io_slave_arid    = 0;
  // assign io_slave_arlen   = 0;
  // assign io_slave_arsize  = 0;
  // assign io_slave_arburst = 0;
  // assign io_slave_rready  = 0;
endmodule


module PMemReader (
    input         clock,
    input         reset,
    output        io_master_arready,
    input         io_master_arvalid,
    input  [31:0] io_master_araddr,
    // input  [3:0]  io_master_arid    ,
    // input  [7:0]  io_master_arlen   ,
    // input  [2:0]  io_master_arsize  ,
    // input  [1:0]  io_master_arburst ,
    input         io_master_rready,
    output        io_master_rvalid,
    output [ 1:0] io_master_rresp,
    output [31:0] io_master_rdata     // ,
    // output        io_master_rlast   ,
    // output [3:0]  io_master_rid     ,

);
  import "DPI-C" function int unsigned pmem_read(input int unsigned raddr);

  // wire [4:0] curr_delay = 5'b00001;
  wire [4:0] curr_delay;
  lfsr_1_to_32 lfsr (
      .clk(clock),
      .rst(reset),
      .rand_out(curr_delay)
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

  always_comb begin
    unique case (state)
      IDLE:  next_state = io_master_arvalid ? RECV : IDLE;
      RECV:  next_state = SERVE;
      SERVE: next_state = (delay_remain == 1) ? HOLD : SERVE;
      HOLD:  next_state = io_master_rready ? IDLE : HOLD;
    endcase
  end

  reg [31:0] rdata;
  always_ff @(posedge clock) begin
    if (reset) begin
      rdata <= 0;
      state <= IDLE;
      delay_remain <= 0;
    end else begin
      state <= next_state;
      if (state == RECV) begin
        delay_remain <= {{27{1'b0}}, curr_delay};
        rdata <= pmem_read(io_master_araddr);
      end else begin
        if (state == SERVE) delay_remain <= delay_remain - 1;
      end

      // if (state == RECV || state == SERVE)
      //   $strobe("> Reader State %x counter %d req %d", state, delay_remain, io_master_arvalid);
    end
  end

  req_not_conflict :
  assert property (@(posedge clock) (io_master_arvalid) |-> (state == IDLE));
  no_count_at_idle :
  assert property (@(posedge clock) (delay_remain != 0) |-> (state == SERVE));

  assign io_master_rvalid  = state == HOLD;
  assign io_master_rdata   = {32{io_master_rvalid}} & rdata;
  assign io_master_arready = state == IDLE;
  assign io_master_rresp   = 2'b00;
endmodule


module PMemWriter (
    input         clock,
    input         reset,
    output        io_master_awready,
    input         io_master_awvalid,
    input  [31:0] io_master_awaddr,
    // input  [3:0]  io_master_awid    ,
    // input  [7:0]  io_master_awlen   ,
    // input  [2:0]  io_master_awsize  ,
    // input  [1:0]  io_master_awburst ,
    output        io_master_wready,
    input         io_master_wvalid,
    input  [31:0] io_master_wdata,
    input  [ 3:0] io_master_wstrb,
    // input         io_master_wlast   ,
    input         io_master_bready,
    output        io_master_bvalid,
    output [ 1:0] io_master_bresp     // ,
    // output [3:0]  io_master_bid     ,
);
  import "DPI-C" function void pmem_write(
    input int unsigned  waddr,
    input int unsigned  wdata,
    input byte unsigned wmask
  );


  wire [4:0] curr_delay;
  lfsr_1_to_32 lfsr (
      .clk(clock),
      .rst(reset),
      .rand_out(curr_delay)
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

  // NOTE: Temporary, not required
  write_addr_data_timing :
  assert property (@(posedge clock) (io_master_awvalid ^ io_master_wvalid));

  always_comb begin
    unique case (state)
      IDLE:  next_state = io_master_awvalid ? RECV : IDLE;
      RECV:  next_state = SERVE;
      SERVE: next_state = (delay_remain == 1) ? HOLD : SERVE;
      HOLD:  next_state = io_master_bready ? IDLE : HOLD;
    endcase
  end

  always_ff @(posedge clock) begin
    if (reset) begin
      state <= IDLE;
      delay_remain <= 0;
    end else begin
      state <= next_state;
      if (state == RECV) begin
        delay_remain <= {{27{1'b0}}, curr_delay};
        pmem_write(io_master_awaddr, io_master_wdata, {{4'h0}, io_master_wstrb});
      end else begin
        if (state == SERVE) delay_remain <= delay_remain - 1;
      end

      // if (state == RECV || state == SERVE)
      //   $strobe("> Writer State %x counter %d req %d", state, delay_remain, io_master_awvalid);
    end
  end

  req_not_conflict :
  assert property (@(posedge clock) (io_master_awvalid) |-> (state == IDLE));

  no_count_at_idle :
  assert property (@(posedge clock) (delay_remain != 0) |-> (state == SERVE));

  assign io_master_bvalid  = state == HOLD;
  assign io_master_awready = state == IDLE;
  assign io_master_wready  = state == IDLE;
  assign io_master_bresp   = 2'b00;
endmodule


module lfsr_1_to_32 (
    input  logic       clk,
    input  logic       rst,
    output logic [4:0] rand_out
);

  localparam int N = 5;
  logic [N-1:0] lfsr_reg;
  logic         feedback_bit;
  assign feedback_bit = lfsr_reg[N-1] ^ lfsr_reg[N-2] ^ lfsr_reg[0];

  always_ff @(posedge clk) begin
    if (rst) begin
      lfsr_reg <= 'b10001;
    end else begin
      lfsr_reg <= {feedback_bit, lfsr_reg[N-1:1]};
    end
    // $strobe("Current out = %x", lfsr_reg);
  end

  assign rand_out = (~(|lfsr_reg)) ? 5'b00001 : lfsr_reg;
endmodule

