module EcallBox (
  input clock,
  input reset,
  input isEcall,
  input isEbreak,
  input bit [31:0] pcin,
  input bit [31:0] a10in
);

  import "DPI-C" function void call_ebreak(
    input bit [31:0] pc,
    input bit [31:0] a10reg
  );

  always @(posedge clock or posedge reset) begin
    if (reset) begin end
    else begin
      if (isEbreak) begin
        call_ebreak(pcin, a10in);
      end

      if (isEcall) begin
        $display("Unknown ECALL\n");
      end
    end
  end

endmodule
