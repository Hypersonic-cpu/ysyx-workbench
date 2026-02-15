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
  always_ff @(posedge clock) begin : fenceI
    if (io_flush) axi_cache_flush(io_simid);
  end

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
      .io_master_rid    (io_master_rid),
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
      .io_master_bid    (io_master_bid),
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
    input int unsigned raddr,
    output int unsigned rdata,
    input shortint unsigned id
  );

  // wire [4:0] curr_delay = 10;
  // lfsr_1_to_32 lfsr (
  //     .clk(clock),
  //     .rst(reset),
  //     .rand_out(curr_delay)
  // );

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
        rid_latch <= io_master_arid;
        // delay_remain <= {{27{1'b0}}, curr_delay};
        delay_remain <= axi_read(io_master_araddr, rdata, io_simid);
      end else begin
        if (state == SERVE) delay_remain <= delay_remain - 1;
      end

      // if (state == RECV || state == SERVE)
      //   $strobe("> Reader State %x counter %d req %d", state, delay_remain, io_master_arvalid);
    end
  end

  // req_not_conflict :
  // assert property (@(posedge clock) (io_master_arvalid) |->
  //   (state == IDLE || (state == HOLD && io_master_rready)));
  no_count_at_idle :
  assert property (@(posedge clock) (delay_remain != 0) |-> (state == SERVE));

  assign io_master_rvalid  = state == HOLD;
  assign io_master_rdata   = {32{io_master_rvalid}} & rdata;
  assign io_master_arready = state == IDLE;
  assign io_master_rresp   = 2'b00;
  assign io_master_rlast   = io_master_rvalid;
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
    input int unsigned waddr,
    input int unsigned wdata,
    input byte unsigned wmask,
    input shortint unsigned id
  );


  wire [4:0] curr_delay = 10;
  // lfsr_1_to_32 lfsr (
  //     .clk(clock),
  //     .rst(reset),
  //     .rand_out(curr_delay)
  // );

  typedef enum logic [1:0] {
    IDLE  = 2'h0,
    RECV  = 2'h1,
    SERVE = 2'h2,
    HOLD  = 2'h3
  } state_t;
  state_t state;
  state_t next_state;
  reg [31:0] delay_remain;
  reg [3:0] bid_latch;

  // NOTE: Temporary, not required
  // write_addr_data_timing :
  // assert property (@(posedge clock) (io_master_awvalid ^ io_master_wvalid));

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
        bid_latch <= io_master_awid;
        // delay_remain <= {{27{1'b0}}, curr_delay};
        delay_remain <= axi_write(
            io_master_awaddr, io_master_wdata, {{4'h0}, io_master_wstrb}, io_simid
        );
      end else begin
        if (state == SERVE) delay_remain <= delay_remain - 1;
      end

      // if (state == RECV || state == SERVE)
      //   $strobe("> Writer State %x counter %d req %d", state, delay_remain, io_master_awvalid);
    end
  end

  // req_not_conflict :
  // assert property (@(posedge clock) (io_master_awvalid) |->
  //   (state == IDLE || (state == HOLD && io_master_bready)));

  no_count_at_idle :
  assert property (@(posedge clock) (delay_remain != 0) |-> (state == SERVE));

  assign io_master_bvalid  = state == HOLD;
  assign io_master_awready = state == IDLE;
  assign io_master_wready  = state == IDLE;
  assign io_master_bresp   = 2'b00;
endmodule


// module lfsr_1_to_32 (
//     input  logic       clk,
//     input  logic       rst,
//     output logic [4:0] rand_out
// );
//
//   localparam int N = 5;
//   logic [N-1:0] lfsr_reg;
//   logic         feedback_bit;
//   assign feedback_bit = lfsr_reg[N-1] ^ lfsr_reg[N-2] ^ lfsr_reg[0];
//
//   always_ff @(posedge clk) begin
//     if (rst) begin
//       lfsr_reg <= 'b10001;
//     end else begin
//       lfsr_reg <= {feedback_bit, lfsr_reg[N-1:1]};
//     end
//     // $strobe("Current out = %x", lfsr_reg);
//   end
//
//   assign rand_out = (~(|lfsr_reg)) ? 5'b00001 : lfsr_reg;
// endmodule
