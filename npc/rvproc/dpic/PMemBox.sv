module PMemBox (
    input clock,
    input reset,
    input reqValid,
    input respReady,
    input [31:0] addr,
    input wrEn,
    input [31:0] wrData,
    input [7:0] byteMask,
    output reqReady,
    output respValid,
    output [31:0] respData
);

  import "DPI-C" function void pmem_init();
  import "DPI-C" function int unsigned pmem_read(input int unsigned raddr);
  import "DPI-C" function void pmem_write(
    input int unsigned  waddr,
    input int unsigned  wdata,
    input byte unsigned wmask
  );

  initial begin
    pmem_init();
  end

  // localparam int DelayCycles = 5;
  wire [4:0] curr_delay;
  lfsr_1_to_32 lfsr (
      .clk(clock),
      .rst(reset),
      .rand_out(curr_delay)
  );

  typedef enum logic [1:0] {
    IDLE  = 2'h0,
    SERVE = 2'h1,
    HOLD  = 2'h3
  } state_t;
  state_t state;
  state_t next_state;
  reg [31:0] delay_remain;

  always_comb begin
    unique case (state)
      IDLE:  next_state = reqValid ? SERVE : IDLE;
      SERVE: next_state = (delay_remain == 1) ? HOLD : SERVE;
      HOLD:  next_state = respReady ? IDLE : HOLD;
    endcase
  end

  reg [31:0] rdata;
  always_ff @(posedge clock) begin
    // $strobe("State %x counter %d req %d", state, delay_remain, reqValid);
    if (reset) begin
      rdata <= 0;
      state <= IDLE;
      delay_remain <= 0;
    end else begin
      state <= next_state;
      if (reqValid) begin
        delay_remain <= {{27{1'b0}}, curr_delay};
        if (wrEn) begin
          pmem_write(addr, wrData, byteMask);
          rdata <= 0;
        end else begin
          rdata <= pmem_read(addr);
        end
      end else begin
        if (state == SERVE) delay_remain <= delay_remain - 1;
      end
    end
  end

  req_not_conflict :
  assert property (@(posedge clock) (reqValid) |-> (state == IDLE));

  no_count_at_idle :
  assert property (@(posedge clock) (delay_remain != 0) |-> (state == SERVE));

  assign respValid = state == HOLD;
  assign reqReady  = state == IDLE;
  assign respData  = {32{respValid}} & rdata;
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

