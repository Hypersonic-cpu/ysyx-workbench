module EcallBox (
  input clock,
  input reset,
  input isEcall,
  input isEbreak,
  input bit [31:0] pcin,
  input bit [31:0] a0in
);

  import "DPI-C" function void call_ebreak(
    input int unsigned pc,
    input int unsigned a0reg
  );

  always @(posedge clock or posedge reset) begin
    if (reset) begin end
    else begin
      if (isEbreak) begin
        // $display(">>>>>> %x\n", pcin);
        // $display(">>>>>> %x\n", a0in);
        call_ebreak(pcin, a0in);
        // $finish;
      end

      if (isEcall) begin
        $display("Unknown ECALL\n");
      end
    end
  end

endmodule
