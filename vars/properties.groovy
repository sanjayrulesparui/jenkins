def call(inputKey){

/************************************************************
********************Jenkins Properties***********************/
	
	def properties = 
	[
		//box folder ids for mule project upload
		boxDidevESBid: '65448462358',
		boxDidevAPIid: '65441650592',
		boxDitstESBid: '70459946469',
		boxDitstAPIid: '70461049387',
		boxDiuatESBid: '68492444248',
		boxDiuatAPIid: '68492462667',
		boxDiprdESBid: '72772445390',
		boxDiprdAPIid: '72771781711',

		// Mule4
		boxDicnvV4ESBid: '133384239158',
		boxDicnvV4APIid: '133383561129',
		boxDiptcV4ESBid: '131502015831',
		boxDiptcV4APIid: '131501463388',
		boxDiqasV4ESBid: '129551386257',
		boxDiqasV4APIid: '129551853475',
		boxDiintV4ESBid: '128538017626',
		boxDiintV4APIid: '128537878319',
		boxDidevV4ESBid: '116327976529',
		boxDidevV4APIid: '116327941759',
		boxDitstV4ESBid: '124233106076',
		boxDitstV4APIid: '124233486650',
		boxDiuatV4ESBid: '117997854755',
		boxDiuatV4APIid: '117998111891',
		boxDiprdV4ESBid: '124233504875',
		boxDiprdV4APIid: '124233455226',

		//user id for 'As-User' header
		boxUser: '8674489077',

		//Group names for deployment
		muleDevTstDeploymentGroup: 'MuleJenkinsDevelopers',
		muleUatPrdDeploymentGroup: 'MuleJenkinsDeployers'
	]

/************************************************************/

	//get property given key parameter
	return properties[inputKey]
}