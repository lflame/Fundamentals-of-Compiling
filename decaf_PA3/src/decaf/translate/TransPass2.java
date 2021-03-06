package decaf.translate;

import java.util.Stack;

import decaf.tree.Tree;
import decaf.tree.Tree.AttachedStmtBlock;
import decaf.tree.Tree.Foreach;
import decaf.backend.OffsetCounter;
import decaf.machdesc.Intrinsic;
import decaf.symbol.Variable;
import decaf.tac.Label;
import decaf.tac.Tac;
import decaf.tac.Temp;
import decaf.type.BaseType;
import decaf.type.ClassType;

public class TransPass2 extends Tree.Visitor {

	static public boolean isDebug = true ;
	
	private Translater tr;

	private Temp currentThis;		//当前的"this"

	private Stack<Label> loopExits;

	public TransPass2(Translater tr) {
		this.tr = tr;
		loopExits = new Stack<Label>();
	}

	@Override
	public void visitClassDef(Tree.ClassDef classDef) {
		for (Tree f : classDef.fields) {
			f.accept(this);
		}
	}

	@Override
	public void visitMethodDef(Tree.MethodDef funcDefn) {
		if (!funcDefn.statik) {
			currentThis = ((Variable) funcDefn.symbol.getAssociatedScope()
					.lookup("this")).getTemp();				//设定currentThis
		}
		tr.beginFunc(funcDefn.symbol);						//开始函数
		funcDefn.body.accept(this);
		tr.endFunc();										//结束函数
		currentThis = null;
	}

	@Override
	public void visitTopLevel(Tree.TopLevel program) {
		for (Tree.ClassDef cd : program.classes) {
			cd.accept(this);
		}
	}

	@Override
	public void visitScopy(Tree.Scopy scopy) {
		scopy.ident.accept(this);
		scopy.expr.accept(this);
		Temp esz = tr.genLoadImm4(4);
		Temp addr1 = tr.genAdd(scopy.ident.val, esz);
		Temp addr2 = tr.genAdd(scopy.expr.val, esz);
		//tr.genDebug("Start!!");
		for(int i=2;i<=scopy.classSymbol.getSize()/4;++i) {
			Temp tmp = tr.genLoad(addr2, 0) ;
			tr.genStore(tmp, addr1, 0);
			addr1=tr.genAdd(addr1, esz) ;
			addr2=tr.genAdd(addr2, esz) ;
		}
	}
	
	@Override
	public void visitGuardedIf(Tree.GuardedIf gif) {
		for(Tree.IfSubStmt t : gif.fields) {
			t.accept(this);
		}
	}
	
	@Override
	public void visitIfSubStmt(Tree.IfSubStmt ifSubStmt) {
		ifSubStmt.expr.accept(this);
		Label exit = Label.createLabel();
		tr.genBeqz(ifSubStmt.expr.val , exit);
		ifSubStmt.stmt.accept(this);
		tr.genMark(exit);
	}
	
	@Override
	public void visitForeach(Foreach foreach) {
		foreach.varDef.accept(this);
		foreach.expr1.accept(this);		
		Label loop = Label.createLabel(), exit = Label.createLabel();
		
		Temp length = tr.genLoad(foreach.expr1.val, -OffsetCounter.WORD_SIZE);
		Temp index = Temp.createTempI4() , wSize = Temp.createTempI4() , one = Temp.createTempI4() ;
		Temp addr = Temp.createTempI4() ;
		tr.genAssign(index, Temp.createConstTemp(0));
		tr.genAssign(wSize, Temp.createConstTemp(OffsetCounter.WORD_SIZE));
		tr.genAssign(one, Temp.createConstTemp(1));
		tr.genAssign(addr, foreach.expr1.val);
		
		tr.genMark(loop);	
		Temp cond = tr.genGeq(index, length) ;
		tr.genBnez(cond, exit);

		Temp tmp = tr.genLoad(addr, 0) ;
		tr.genAssign(foreach.varDef.symbol.getTemp(), tmp);
		foreach.expr2.accept(this);
		tr.genBeqz(foreach.expr2.val, exit) ;
		loopExits.push(exit);
		foreach.attBlock.accept(this);
		loopExits.pop();
		
		tr.append(Tac.genAdd(index, index, one));
		tr.append(Tac.genAdd(addr , addr, wSize));
		tr.genBranch(loop);
		tr.genMark(exit);
	}
	
	@Override
	public void visitAttachedStmtBlock(AttachedStmtBlock attachedStmtBlock) {
		for(Tree t : attachedStmtBlock.block) {
			t.accept(this);
		}
	}
	
	@Override
	public void visitDefaultArrayRef(Tree.DefaultArrayRef defaultArrayRef) {
		defaultArrayRef.expr.accept(this);
		defaultArrayRef.index.accept(this);
		defaultArrayRef.deft.accept(this);
		defaultArrayRef.val = Temp.createTempI4() ;
		Label lDeft = Label.createLabel() , exit = Label.createLabel() ;
		Temp zero = Temp.createTempI4() , wSize = Temp.createTempI4() ;
		tr.genAssign(zero, Temp.createConstTemp(0));
		tr.genAssign(wSize, Temp.createConstTemp(OffsetCounter.WORD_SIZE));
		Temp cond = tr.genGeq(defaultArrayRef.index.val, zero) ;
		tr.genBeqz(cond, lDeft);
		Temp length = tr.genLoad(defaultArrayRef.expr.val, -OffsetCounter.WORD_SIZE);
		cond = tr.genLes(defaultArrayRef.index.val, length);
		tr.genBeqz(cond, lDeft);  //此后判定为合法
		Temp addr = tr.genMul(defaultArrayRef.index.val, wSize) ;
		addr = tr.genAdd(addr, defaultArrayRef.expr.val) ;
		tr.genAssign(defaultArrayRef.val , tr.genLoad(addr , 0)) ;
		tr.genBranch(exit);
		
		tr.genMark(lDeft);
		tr.genAssign(defaultArrayRef.val, defaultArrayRef.deft.val);
		tr.genMark(exit);
	}
	
	@Override
	public void visitVarDef(Tree.VarDef varDef) {
		if (varDef.symbol.isLocalVar()) {					//处理局部变量，建立对应Temp
			Temp t = Temp.createTempI4();
			t.sym = varDef.symbol;
			varDef.symbol.setTemp(t);
		}
	}
	
	@Override
	public void visitBinary(Tree.Binary expr) {
		expr.left.accept(this);
		expr.right.accept(this);
		
		if(expr.tag==Tree.DMOD) {
			Temp one=Temp.createTempI4() , wSize=Temp.createTempI4() ;
			tr.genAssign(one, Temp.createConstTemp(1)); 
			tr.genAssign(wSize, Temp.createConstTemp(OffsetCounter.WORD_SIZE));
			tr.inDMOD=true ;
			expr.val = tr.genNewArray(expr.right.val) ;
			tr.inDMOD=false ;
			Temp cnt=Temp.createTempI4(), addr=Temp.createTempI4() ;
			tr.genAssign(cnt, expr.right.val);
			tr.genAssign(addr, expr.val);
			
			//While-Loop
			Label loop = Label.createLabel();
			tr.genMark(loop);			
			Label exit = Label.createLabel();
			tr.genBeqz(cnt, exit);
		
			//Loop content
			if(expr.left.type.isClassType()) {
				//浅赋值
				ClassType tmpCT = (ClassType)expr.left.type ;
				Temp newClass = tr.genDirectCall(tmpCT.getSymbol().getNewFuncLabel(),
						BaseType.INT);
				Temp addr1 = Temp.createTempI4() , addr2 = Temp.createTempI4() ;
				tr.genAssign(addr1, newClass);
				tr.genAssign(addr2, expr.left.val);
				for(int i=1;i<=tmpCT.getSymbol().getSize()/4;++i) {
					Temp tmp = tr.genLoad(addr2, 0) ;
					tr.genStore(tmp, addr1, 0);
					addr1=tr.genAdd(addr1, wSize) ;
					addr2=tr.genAdd(addr2, wSize) ;
				}			
				tr.genStore(newClass, addr, 0);
			}
			else	tr.genStore(expr.left.val, addr, 0);
			
			//Loop end
			tr.append(Tac.genAdd(addr, addr, wSize));
			tr.append(Tac.genSub(cnt, cnt, one));
			tr.genBranch(loop);
			tr.genMark(exit);
		}
		switch (expr.tag) {
		case Tree.PLUS:
			expr.val = tr.genAdd(expr.left.val, expr.right.val);
			break;
		case Tree.MINUS:
			expr.val = tr.genSub(expr.left.val, expr.right.val);
			break;
 		case Tree.MUL:
			expr.val = tr.genMul(expr.left.val, expr.right.val);
			break;
		case Tree.DIV:
			expr.val = tr.genDiv(expr.left.val, expr.right.val);
			break;
		case Tree.MOD:
			expr.val = tr.genMod(expr.left.val, expr.right.val);
			break;
		case Tree.AND:
			expr.val = tr.genLAnd(expr.left.val, expr.right.val);
			break;
		case Tree.OR:
			expr.val = tr.genLOr(expr.left.val, expr.right.val);
			break;
		case Tree.LT:
			expr.val = tr.genLes(expr.left.val, expr.right.val);
			break;
		case Tree.LE:
			expr.val = tr.genLeq(expr.left.val, expr.right.val);
			break;
		case Tree.GT:
			expr.val = tr.genGtr(expr.left.val, expr.right.val);
			break;
		case Tree.GE:
			expr.val = tr.genGeq(expr.left.val, expr.right.val);
			break;
		case Tree.EQ:
		case Tree.NE:
			genEquNeq(expr);						//相等和不等单独处理(因为操作数可以为字符串)
			break;
		}
	}

	private void genEquNeq(Tree.Binary expr) {
		if (expr.left.type.equal(BaseType.STRING)
				|| expr.right.type.equal(BaseType.STRING)) {
			tr.genParm(expr.left.val);
			tr.genParm(expr.right.val);
			expr.val = tr.genDirectCall(Intrinsic.STRING_EQUAL.label,
					BaseType.BOOL);
			if(expr.tag == Tree.NE){
				expr.val = tr.genLNot(expr.val);
			}
		} else {
			if(expr.tag == Tree.EQ)
				expr.val = tr.genEqu(expr.left.val, expr.right.val);
			else
				expr.val = tr.genNeq(expr.left.val, expr.right.val);
		}
	}

	@Override
	public void visitAssign(Tree.Assign assign) {				//判断左值类型并处理赋值语句
		assign.left.accept(this);
		assign.expr.accept(this);
		switch (assign.left.lvKind) {
		case ARRAY_ELEMENT:										//左值为数组元素,涉及访存
			Tree.Indexed arrayRef = (Tree.Indexed) assign.left;
			Temp esz = tr.genLoadImm4(OffsetCounter.WORD_SIZE);
			Temp t = tr.genMul(arrayRef.index.val, esz);
			Temp base = tr.genAdd(arrayRef.array.val, t);
			tr.genStore(assign.expr.val, base, 0);
			break;
		case MEMBER_VAR:										//左值为成员变量,涉及访存
			Tree.Ident varRef = (Tree.Ident) assign.left;
			tr.genStore(assign.expr.val, varRef.owner.val, varRef.symbol
					.getOffset());
			break;
		case PARAM_VAR:
		case LOCAL_VAR:											//左值为参变量或局部变量,不涉及访存
			tr.genAssign(((Tree.Ident) assign.left).symbol.getTemp(),
					assign.expr.val);/////getTemp()不存在
			break;
		}
	}

	@Override
	public void visitLiteral(Tree.Literal literal) {			//处理常量
		switch (literal.typeTag) {
		case Tree.INT:
			literal.val = tr.genLoadImm4(((Integer)literal.value).intValue());
			break;
		case Tree.BOOL:
			literal.val = tr.genLoadImm4((Boolean)(literal.value) ? 1 : 0);
			break;
		default:
			literal.val = tr.genLoadStrConst((String)literal.value);
		}
	}

	@Override
	public void visitExec(Tree.Exec exec) {
		exec.expr.accept(this);
	}

	@Override
	public void visitUnary(Tree.Unary expr) {
		expr.expr.accept(this);
		switch (expr.tag){
		case Tree.NEG:
			expr.val = tr.genNeg(expr.expr.val);
			break;
		default:
			expr.val = tr.genLNot(expr.expr.val);
		}
	}

	@Override
	public void visitNull(Tree.Null nullExpr) {
		nullExpr.val = tr.genLoadImm4(0);
	}

	@Override
	public void visitBlock(Tree.Block block) {
		for (Tree s : block.block) {
			s.accept(this);
		}
	}

	@Override
	public void visitThisExpr(Tree.ThisExpr thisExpr) {
		thisExpr.val = currentThis;
	}

	@Override
	public void visitReadIntExpr(Tree.ReadIntExpr readIntExpr) {
		readIntExpr.val = tr.genIntrinsicCall(Intrinsic.READ_INT);
	}

	@Override
	public void visitReadLineExpr(Tree.ReadLineExpr readStringExpr) {
		readStringExpr.val = tr.genIntrinsicCall(Intrinsic.READ_LINE);
	}

	@Override
	public void visitReturn(Tree.Return returnStmt) {
		if (returnStmt.expr != null) {
			returnStmt.expr.accept(this);
			tr.genReturn(returnStmt.expr.val);
		} else {
			tr.genReturn(null);
		}

	}

	@Override
	public void visitPrint(Tree.Print printStmt) {
		for (Tree.Expr r : printStmt.exprs) {
			r.accept(this);
			tr.genParm(r.val);
			if (r.type.equal(BaseType.BOOL)) {
				tr.genIntrinsicCall(Intrinsic.PRINT_BOOL);
			} else if (r.type.equal(BaseType.INT)) {
				tr.genIntrinsicCall(Intrinsic.PRINT_INT);
			} else if (r.type.equal(BaseType.STRING)) {
				tr.genIntrinsicCall(Intrinsic.PRINT_STRING);
			}
		}
	}

	@Override
	public void visitIndexed(Tree.Indexed indexed) {
		indexed.array.accept(this);
		indexed.index.accept(this);
		tr.genCheckArrayIndex(indexed.array.val, indexed.index.val);
		
		Temp esz = tr.genLoadImm4(OffsetCounter.WORD_SIZE);
		Temp t = tr.genMul(indexed.index.val, esz);
		Temp base = tr.genAdd(indexed.array.val, t);
		indexed.val = tr.genLoad(base, 0);
	}

	@Override
	public void visitIdent(Tree.Ident ident) {
		if(ident.lvKind == Tree.LValue.Kind.MEMBER_VAR){
			ident.owner.accept(this);
		}
		if (ident.isVar==true) {					//处理局部变量，建立对应Temp
			Temp t = Temp.createTempI4();
			t.sym = ident.symbol;
			ident.symbol.setTemp(t);
		}
		
		switch (ident.lvKind) {
		case MEMBER_VAR:
			ident.val = tr.genLoad(ident.owner.val, ident.symbol.getOffset());
			break;
		default:
			ident.val = ident.symbol.getTemp();
			break;
		}
	}
	
	@Override
	public void visitBreak(Tree.Break breakStmt) {
		tr.genBranch(loopExits.peek());
	}

	@Override
	public void visitCallExpr(Tree.CallExpr callExpr) {
		if (callExpr.isArrayLength) {
			callExpr.receiver.accept(this);
			callExpr.val = tr.genLoad(callExpr.receiver.val,
					-OffsetCounter.WORD_SIZE);
		} else {
			if (callExpr.receiver != null) {
				callExpr.receiver.accept(this);
			}
			for (Tree.Expr expr : callExpr.actuals) {
				expr.accept(this);
			}
			if (callExpr.receiver != null) {
				tr.genParm(callExpr.receiver.val);
			}
			for (Tree.Expr expr : callExpr.actuals) {
				tr.genParm(expr.val);
			}
			if (callExpr.receiver == null) {
				callExpr.val = tr.genDirectCall(
						callExpr.symbol.getFuncty().label, callExpr.symbol
								.getReturnType());
			} else {
				Temp vt = tr.genLoad(callExpr.receiver.val, 0);
				Temp func = tr.genLoad(vt, callExpr.symbol.getOffset());
				callExpr.val = tr.genIndirectCall(func, callExpr.symbol
						.getReturnType());
			}
		}

	}

	@Override
	public void visitForLoop(Tree.ForLoop forLoop) {
		if (forLoop.init != null) {
			forLoop.init.accept(this);
		}
		Label cond = Label.createLabel();
		Label loop = Label.createLabel();
		tr.genBranch(cond);
		tr.genMark(loop);
		if (forLoop.update != null) {
			forLoop.update.accept(this);
		}
		tr.genMark(cond);
		forLoop.condition.accept(this);
		Label exit = Label.createLabel();
		tr.genBeqz(forLoop.condition.val, exit);
		loopExits.push(exit);
		if (forLoop.loopBody != null) {
			forLoop.loopBody.accept(this);
		}
		tr.genBranch(loop);
		loopExits.pop();
		tr.genMark(exit);
	}

	@Override
	public void visitIf(Tree.If ifStmt) {
		ifStmt.condition.accept(this);
		if (ifStmt.falseBranch != null) {
			Label falseLabel = Label.createLabel();
			tr.genBeqz(ifStmt.condition.val, falseLabel);
			ifStmt.trueBranch.accept(this);
			Label exit = Label.createLabel();
			tr.genBranch(exit);
			tr.genMark(falseLabel);
			ifStmt.falseBranch.accept(this);
			tr.genMark(exit);
		} else if (ifStmt.trueBranch != null) {
			Label exit = Label.createLabel();
			tr.genBeqz(ifStmt.condition.val, exit);
			if (ifStmt.trueBranch != null) {
				ifStmt.trueBranch.accept(this);
			}
			tr.genMark(exit);
		}
	}

	@Override
	public void visitNewArray(Tree.NewArray newArray) {
		newArray.length.accept(this);
		newArray.val = tr.genNewArray(newArray.length.val);
	}

	@Override
	public void visitNewClass(Tree.NewClass newClass) {
		newClass.val = tr.genDirectCall(newClass.symbol.getNewFuncLabel(),
				BaseType.INT);
	}

	@Override
	public void visitWhileLoop(Tree.WhileLoop whileLoop) {
		Label loop = Label.createLabel();
		tr.genMark(loop);
		whileLoop.condition.accept(this);
		Label exit = Label.createLabel();
		tr.genBeqz(whileLoop.condition.val, exit);
		loopExits.push(exit);
		if (whileLoop.loopBody != null) {
			whileLoop.loopBody.accept(this);
		}
		tr.genBranch(loop);
		loopExits.pop();
		tr.genMark(exit);
	}

	@Override
	public void visitTypeTest(Tree.TypeTest typeTest) {
		typeTest.instance.accept(this);
		typeTest.val = tr.genInstanceof(typeTest.instance.val,
				typeTest.symbol);
	}

	@Override
	public void visitTypeCast(Tree.TypeCast typeCast) {
		typeCast.expr.accept(this);
		if (!typeCast.expr.type.compatible(typeCast.symbol.getType())) {
			tr.genClassCast(typeCast.expr.val, typeCast.symbol);
		}
		typeCast.val = typeCast.expr.val;
	}
}
