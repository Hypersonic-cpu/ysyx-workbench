package rvproc.cache

import chisel3._
import chisel3.util._

// Pseudo-LRU replacement policy for set-associative caches.
// Generator-style: parameterized by associativity (1, 2, or 4).
//
// Tree structure for 4-way:
//        b0 (root)
//       /    \
//      b1     b2
//     / \    / \
//    w0 w1  w2 w3
//
// Eviction: b0 ? (b1 ? w0 : w1) : (b2 ? w2 : w3)
// Update: set bits along path to point away from accessed way
object PLRU {

  // Get victim way to evict based on current PLRU bits
  def getVictim(plru: UInt, assoc: Int): UInt = {
    assoc match {
      case 1 => 0.U
      case 2 => Mux(plru(0), 0.U, 1.U)
      case 4 =>
        val b0 = plru(0)
        val b1 = plru(1)
        val b2 = plru(2)
        Mux(b0, Mux(b1, 0.U, 1.U), Mux(b2, 2.U, 3.U))
      case _ =>
        throw new IllegalArgumentException(s"Unsupported assoc: $assoc")
    }
  }

  // Update PLRU bits after accessing a way
  def update(plru: UInt, way: UInt, assoc: Int): UInt = {
    assoc match {
      case 1 => 0.U
      case 2 => ~way(0)
      case 4 =>
        val b0    = plru(0)
        val b1    = plru(1)
        val b2    = plru(2)
        val newB0 = ~way(1)
        val newB1 = Mux(way(1), b1, ~way(0))
        val newB2 = Mux(way(1), ~way(0), b2)
        Cat(newB2, newB1, newB0)
      case _ =>
        throw new IllegalArgumentException(s"Unsupported assoc: $assoc")
    }
  }

  // Get width of PLRU bits for given associativity
  def width(assoc: Int): Int = if (assoc > 1) assoc - 1 else 1
}
