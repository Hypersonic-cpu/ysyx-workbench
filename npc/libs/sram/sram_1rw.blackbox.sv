(* blackbox *)
module sram_1rw_26x8 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [2:0] addr0,
  input  [25:0] din0,
  output [25:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_128x8 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [2:0] addr0,
  input  [127:0] din0,
  output [127:0] dout0
);
endmodule
