(* blackbox *)
module sram_1rw_24x32 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [4:0] addr0,
  input  [23:0] din0,
  output [23:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_128x32 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [4:0] addr0,
  input  [127:0] din0,
  output [127:0] dout0
);
endmodule
