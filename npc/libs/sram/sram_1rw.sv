// Behavioral 1RW SRAM model (parameterized).
// Drop-in replacement for SyncReadMem when GlbCtrl.sramlib=true.
// For synthesis, replace with OpenRAM-generated technology SRAM.
module sram_1rw #(
  parameter WORD_SIZE = 32,
  parameter NUM_WORDS = 64
) (
  input                              clk0,
  input                              csb0,  // chip select bar
  input                              web0,  // write enable bar
  input  [$clog2(NUM_WORDS) - 1 : 0] addr0,
  input  [WORD_SIZE - 1 : 0]         din0,
  output reg [WORD_SIZE - 1 : 0]     dout0
);
  reg [WORD_SIZE-1:0] mem [0:NUM_WORDS-1];

  always @(posedge clk0) begin
    if (!csb0) begin
      if (!web0) begin
        mem[addr0] <= din0;
      end else begin
        dout0 <= mem[addr0];
      end
    end
  end
endmodule
